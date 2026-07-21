package com.example.sip;

import com.example.sip.cache.TokenCache;
import com.example.sip.config.GatewayConfig;
import com.example.sip.diameter.RealmRouter;
import com.example.sip.diameter.ShClient;
import com.example.sip.identity.MsisdnNormalizer;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.ApnsClient;
import com.example.sip.push.FcmClient;
import com.example.sip.worker.AsyncWorkerPool;
import com.example.sip.worker.RingingProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletResponse;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Intercepts SIP 180 Ringing on INVITE transactions and hands work to async workers.
 * SIP thread work must stay under 5ms (extract + enqueue only).
 */
public class PushNotificationServlet extends SipServlet {

    private static final Logger LOG = LoggerFactory.getLogger(PushNotificationServlet.class);

    private GatewayConfig config;
    private MsisdnNormalizer normalizer;
    private RealmRouter realmRouter;
    private GatewayMetrics metrics;
    private AsyncWorkerPool workerPool;
    private AsyncWorkerPool cleanupPool;
    private TokenCache tokenCache;
    private ShClient shClient;
    private RingingProcessor processor;
    private ScheduledExecutorService evictor;

    @Override
    public void init() throws ServletException {
        super.init();
        config = GatewayConfig.fromEnvironment();
        metrics = new GatewayMetrics();
        normalizer = new MsisdnNormalizer(config);
        realmRouter = new RealmRouter(config);
        tokenCache = new TokenCache(config, java.time.Clock.systemUTC(), metrics::setTokenCacheSize);
        shClient = new ShClient(config);
        shClient.start();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.pushHttpTimeout())
                .build();
        ObjectMapper mapper = new ObjectMapper();
        ApnsClient apnsClient = new ApnsClient(config, httpClient, mapper);
        FcmClient fcmClient = new FcmClient(config, httpClient, mapper);

        workerPool = new AsyncWorkerPool(config, metrics);
        cleanupPool = new AsyncWorkerPool(config, metrics);
        processor = new RingingProcessor(tokenCache, shClient, apnsClient, fcmClient, metrics, cleanupPool);

        evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-cache-evictor");
            t.setDaemon(true);
            return t;
        });
        Duration interval = config.cacheEvictorInterval();
        evictor.scheduleAtFixedRate(
                () -> tokenCache.evictExpired(),
                interval.toSeconds(),
                interval.toSeconds(),
                TimeUnit.SECONDS);

        LOG.info("PushNotificationServlet initialized (defaultRealm={})",
                config.defaultDestinationRealm());
    }

    @Override
    protected void doResponse(SipServletResponse response) throws ServletException, IOException {
        try {
            if (response.getStatus() == SipServletResponse.SC_RINGING
                    && "INVITE".equalsIgnoreCase(response.getMethod())) {
                metrics.incrementSipRinging();
                enqueueRinging(response);
            }
        } catch (Exception e) {
            LOG.warn("Failed to enqueue ringing event: {}", e.toString());
        } finally {
            // Never delay IMS signaling for HSS/HTTP work.
            super.doResponse(response);
        }
    }

    private void enqueueRinging(SipServletResponse response) {
        String callId = response.getCallId();
        String toHeader = header(response, "To");
        String pCalled = header(response, "P-Called-Party-ID");
        String fromHeader = header(response, "From");

        String calledHeader = (pCalled != null && !pCalled.isBlank()) ? pCalled : toHeader;
        Optional<String> calleeMsisdn = normalizer.extractMsisdn(calledHeader);
        if (calleeMsisdn.isEmpty()) {
            LOG.warn("Unable to extract callee MSISDN callId={} header={}", callId, calledHeader);
            return;
        }

        Optional<String> domain = normalizer.extractDomain(calledHeader);
        String destinationRealm = realmRouter.resolve(calleeMsisdn.get(), domain);
        String callerDisplay = normalizer.resolveCallerDisplay(fromHeader);
        Optional<String> callerMsisdn = normalizer.extractMsisdn(fromHeader);

        RingingEvent event = new RingingEvent(
                callId,
                callerDisplay,
                callerMsisdn.orElse(null),
                calleeMsisdn.get(),
                normalizer.extractUri(calledHeader).orElse(null),
                destinationRealm,
                UUID.randomUUID().toString());

        workerPool.execute(() -> processor.process(event));
    }

    private static String header(SipServletResponse response, String name) {
        try {
            return response.getHeader(name);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void destroy() {
        if (evictor != null) {
            evictor.shutdownNow();
        }
        if (workerPool != null) {
            workerPool.close();
        }
        if (cleanupPool != null) {
            cleanupPool.close();
        }
        if (shClient != null) {
            shClient.close();
        }
        super.destroy();
    }
}
