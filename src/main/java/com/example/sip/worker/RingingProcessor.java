package com.example.sip.worker;

import com.example.sip.cache.TokenCache;
import com.example.sip.diameter.ShClientApi;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.PushClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public RingingProcessor(
            TokenCache tokenCache,
            ShClientApi shClient,
            PushClient apnsClient,
            PushClient fcmClient,
            GatewayMetrics metrics,
            AsyncWorkerPool cleanupPool) {
        this.tokenCache = tokenCache;
        this.shClient = shClient;
        this.apnsClient = apnsClient;
        this.fcmClient = fcmClient;
        this.metrics = metrics;
        this.cleanupPool = cleanupPool;
    }

    public void process(RingingEvent event) {
        try {
            Optional<PushTokenRecord> cached = tokenCache.get(event.calleeMsisdn());
            PushTokenRecord record;
            if (cached.isPresent()) {
                metrics.incrementCacheHit();
                record = cached.get();
            } else {
                metrics.incrementCacheMiss();
                metrics.incrementRealmRoute(event.destinationRealm());
                Optional<PushTokenRecord> fromHss = shClient
                        .userDataRequest(event.calleeMsisdn(), event.destinationRealm())
                        .join();
                if (fromHss.isEmpty()) {
                    metrics.incrementHssFailure(event.destinationRealm(), "empty_or_error");
                    LOG.warn("No HSS push token callId={} callee={} realm={}",
                            event.callId(), event.calleeMsisdn(), event.destinationRealm());
                    return;
                }
                record = fromHss.get();
                tokenCache.put(event.calleeMsisdn(), record);
            }

            PushClient client = record.platform() == PushPlatform.APNS ? apnsClient : fcmClient;
            PushClient.PushResult result = client.send(event, record).join();
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
                    shClient.purgeToken(event.calleeMsisdn(), event.destinationRealm(), nextSeq).join();
                    metrics.incrementTokenPurge();
                });
            }
        } catch (Exception e) {
            LOG.warn("Ringing processing failed callId={}: {}", event.callId(), e.toString());
            metrics.incrementHssFailure(event.destinationRealm(), "processor_error");
        }
    }
}
