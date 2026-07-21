package com.example.sip.worker;

import com.example.sip.cache.TokenCache;
import com.example.sip.diameter.ShClientApi;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.observability.CallMdc;
import com.example.sip.observability.GatewayTracing;
import com.example.sip.push.PushClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Background pipeline: cache → HSS UDR → platform push → optional token PUR.
 */
public final class RingingProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(RingingProcessor.class);

    private final TokenCache tokenCache;
    private final ShClientApi shClient;
    private final PushClient apnsClient;
    private final PushClient fcmClient;
    private final GatewayMetrics metrics;
    private final AsyncWorkerPool cleanupPool;
    private final GatewayTracing tracing;

    public RingingProcessor(
            TokenCache tokenCache,
            ShClientApi shClient,
            PushClient apnsClient,
            PushClient fcmClient,
            GatewayMetrics metrics,
            AsyncWorkerPool cleanupPool) {
        this(tokenCache, shClient, apnsClient, fcmClient, metrics, cleanupPool, new GatewayTracing());
    }

    public RingingProcessor(
            TokenCache tokenCache,
            ShClientApi shClient,
            PushClient apnsClient,
            PushClient fcmClient,
            GatewayMetrics metrics,
            AsyncWorkerPool cleanupPool,
            GatewayTracing tracing) {
        this.tokenCache = tokenCache;
        this.shClient = shClient;
        this.apnsClient = apnsClient;
        this.fcmClient = fcmClient;
        this.metrics = metrics;
        this.cleanupPool = cleanupPool;
        this.tracing = tracing;
    }

    public void process(RingingEvent event) {
        CallMdc.put(event);
        try (GatewayTracing.SpanScope span = tracing.startRingingSpan(event)) {
            try {
                Optional<PushTokenRecord> cached = tokenCache.get(event.calleeMsisdn());
                PushTokenRecord record;
                if (cached.isPresent()) {
                    metrics.incrementCacheHit();
                    record = cached.get();
                } else {
                    metrics.incrementCacheMiss();
                    metrics.incrementRealmRoute(event.destinationRealm());
                    long startNanos = System.nanoTime();
                    Optional<PushTokenRecord> fromHss = Optional.empty();
                    try (GatewayTracing.SpanScope hssSpan = tracing.startChild("gateway.hss.udr")) {
                        fromHss = shClient
                                .userDataRequest(event.calleeMsisdn(), event.destinationRealm())
                                .join();
                        hssSpan.span().setAttribute("hss.hit", fromHss.isPresent());
                    } finally {
                        metrics.recordHssLookup(
                                Duration.ofNanos(System.nanoTime() - startNanos),
                                event.destinationRealm());
                    }
                    if (fromHss.isEmpty()) {
                        metrics.incrementHssFailure(event.destinationRealm(), "empty_or_error");
                        LOG.warn("No HSS push token callId={} callee={} realm={}",
                                event.callId(), event.calleeMsisdn(), event.destinationRealm());
                        return;
                    }
                    record = fromHss.get();
                    tokenCache.put(event.calleeMsisdn(), record);
                }

                CallMdc.putPlatform(record.platform().name());
                span.span().setAttribute("push.platform", record.platform().name());

                PushClient client = record.platform() == PushPlatform.APNS ? apnsClient : fcmClient;
                PushClient.PushResult result;
                try (GatewayTracing.SpanScope pushSpan = tracing.startChild("gateway.push.send")) {
                    pushSpan.span().setAttribute("push.platform", record.platform().name());
                    result = client.send(event, record).join();
                    pushSpan.span().setAttribute("push.success", result.success());
                }
                if (result.success()) {
                    metrics.incrementPushSuccess(record.platform().name());
                    return;
                }

                metrics.incrementPushError(record.platform().name(),
                        result.errorCode() == null ? "unknown" : result.errorCode());

                if (result.tokenInvalid()) {
                    tokenCache.invalidate(event.calleeMsisdn());
                    long nextSeq = record.sequenceNumber() + 1;
                    cleanupPool.execute(() -> {
                        CallMdc.put(event);
                        try {
                            shClient.purgeToken(event.calleeMsisdn(), event.destinationRealm(), nextSeq).join();
                            metrics.incrementTokenPurge();
                        } finally {
                            CallMdc.clear();
                        }
                    });
                }
            } catch (Exception e) {
                span.recordException(e);
                LOG.warn("Ringing processing failed callId={}: {}", event.callId(), e.toString());
                metrics.incrementHssFailure(event.destinationRealm(), "processor_error");
            }
        } finally {
            CallMdc.clear();
        }
    }
}
