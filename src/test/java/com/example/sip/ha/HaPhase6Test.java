package com.example.sip.ha;

import com.example.sip.cache.TokenCache;
import com.example.sip.config.GatewayConfig;
import com.example.sip.diameter.MockHssDiameterTransport;
import com.example.sip.diameter.RealmRouter;
import com.example.sip.diameter.ShClient;
import com.example.sip.harness.FakeShClient;
import com.example.sip.harness.RecordingPushClient;
import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.identity.MsisdnNormalizer;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.resilience.GatewayResilience;
import com.example.sip.resilience.ResilientDiameterTransport;
import com.example.sip.worker.AsyncWorkerPool;
import com.example.sip.worker.RingingProcessor;
import com.example.sip.worker.SipRingingHandoff;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6.1–T6.3 — SIP handler budget probe, HSS chaos fail-fast, multi-node Call-ID policy.
 */
class HaPhase6Test {

    private static final String REALM = RingingEventFixtures.DEFAULT_REALM;
    private static final String CALL_ID = "shared-call-id@ims.example.org";
    private static final String TO = RingingEventFixtures.toHeaderE164();
    private static final String FROM = RingingEventFixtures.fromHeaderE164();

    private GatewayConfig config;
    private GatewayMetrics metrics;
    private AsyncWorkerPool pool;

    @BeforeEach
    void setUp() {
        config = GatewayConfig.builder()
                .cacheMaxEntries(100)
                .cacheTtl(Duration.ofSeconds(300))
                .workerCorePoolSize(2)
                .workerMaxPoolSize(4)
                .workerQueueCapacity(64)
                .diameterMessageTimeout(Duration.ofMillis(500))
                .build();
        metrics = new GatewayMetrics();
        pool = AsyncWorkerPool.sameThread(config, metrics);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void sipHandlerBudgetProbeStaysUnderFiveMillis() {
        AtomicInteger enqueued = new AtomicInteger();
        SipRingingHandoff warm = new SipRingingHandoff(
                new MsisdnNormalizer(config),
                new RealmRouter(config),
                metrics,
                event -> {
                    enqueued.incrementAndGet();
                    pool.execute(() -> {
                    });
                });

        for (int i = 0; i < 200; i++) {
            warm.handoff(CALL_ID + i, TO, null, FROM);
        }

        GatewayMetrics probeMetrics = new GatewayMetrics();
        SipRingingHandoff probe = new SipRingingHandoff(
                new MsisdnNormalizer(config),
                new RealmRouter(config),
                probeMetrics,
                event -> pool.execute(() -> {
                }));

        int iterations = 500;
        long[] samplesNs = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            assertTrue(probe.handoff(CALL_ID + "-probe-" + i, TO, null, FROM));
            samplesNs[i] = System.nanoTime() - start;
        }

        java.util.Arrays.sort(samplesNs);
        long p99 = samplesNs[(int) Math.min(iterations - 1, Math.floor(iterations * 0.99))];
        double p99Ms = p99 / 1_000_000d;
        double meanMs = probeMetrics.sipHandlerMeanMillis();

        assertEquals(iterations, probeMetrics.sipHandlerCount());
        assertTrue(meanMs < SipRingingHandoff.BUDGET.toMillis(),
                "mean sip_handler_latency_ms=" + meanMs);
        assertTrue(p99Ms < SipRingingHandoff.BUDGET.toMillis(),
                "p99 sip_handler_latency_ms=" + p99Ms + " (CI gate: p99 < 5ms per §12)");
        assertTrue(enqueued.get() >= 200);
    }

    @Test
    void hssPeerKillFailsFastWithoutStallingSipHandoff() throws Exception {
        GatewayResilience resilience = GatewayResilience.forTests(2);
        MockHssDiameterTransport mock = new MockHssDiameterTransport()
                .withOpenPeer(REALM, "aaas://hss1." + REALM + ":5658")
                .withForcedResult(RingingEventFixtures.CALLEE_E164, 3002L);
        ResilientDiameterTransport transport =
                new ResilientDiameterTransport(mock, resilience.hssBreaker());
        ShClient shClient = new ShClient(config, transport, metrics);
        shClient.start();

        for (int i = 0; i < 4; i++) {
            shClient.userDataRequest(RingingEventFixtures.CALLEE_E164, REALM)
                    .get(2, TimeUnit.SECONDS);
        }
        assertEquals(CircuitBreaker.State.OPEN, resilience.hssBreaker().getState());

            RecordingPushClient push = new RecordingPushClient();
            java.util.concurrent.CountDownLatch processed = new java.util.concurrent.CountDownLatch(1);
            // Real async pool: SIP handoff must return before HSS work runs (sameThread would stall).
            try (AsyncWorkerPool asyncPool = new AsyncWorkerPool(config, metrics)) {
                RingingProcessor processor = new RingingProcessor(
                        new TokenCache(config),
                        shClient,
                        push,
                        push,
                        metrics,
                        asyncPool);

                GatewayMetrics handoffMetrics = new GatewayMetrics();
                SipRingingHandoff timed = new SipRingingHandoff(
                        new MsisdnNormalizer(config),
                        new RealmRouter(config),
                        handoffMetrics,
                        event -> asyncPool.execute(() -> {
                            try {
                                processor.process(event);
                            } finally {
                                processed.countDown();
                            }
                        }));

                long start = System.nanoTime();
                assertTrue(timed.handoff(CALL_ID, TO, null, FROM));
                long handoffNanos = System.nanoTime() - start;

                assertTrue(
                        handoffNanos < Duration.ofMillis(5).toNanos() * 4,
                        "SIP handoff stalled during HSS outage: " + (handoffNanos / 1_000_000d) + "ms");
                assertTrue(handoffMetrics.sipHandlerMeanMillis() < SipRingingHandoff.BUDGET.toMillis() * 4);

                assertTrue(processed.await(2, TimeUnit.SECONDS), "worker did not finish");
                assertEquals(0, push.all().size());
            }
        shClient.close();
    }

    @Test
    void twoNodesProcessSameCallIdIndependentlyPerLbPolicy() {
        // Spec §7.2: app is stateless; "processed once" is an LB assertion (call-hash / sticky),
        // not application deduplication. Two node caches both accept the same Call-ID.
        List<String> nodeAEvents = new CopyOnWriteArrayList<>();
        List<String> nodeBEvents = new CopyOnWriteArrayList<>();

        GatewayMetrics metricsA = new GatewayMetrics();
        GatewayMetrics metricsB = new GatewayMetrics();
        FakeShClient sh = new FakeShClient().withToken(
                RingingEventFixtures.CALLEE_E164,
                new PushTokenRecord("tok", PushPlatform.APNS, 1));
        RecordingPushClient pushA = new RecordingPushClient();
        RecordingPushClient pushB = new RecordingPushClient();

        RingingProcessor processorA = new RingingProcessor(
                new TokenCache(config), sh, pushA, pushA, metricsA, pool);
        RingingProcessor processorB = new RingingProcessor(
                new TokenCache(config), sh, pushB, pushB, metricsB, pool);

        SipRingingHandoff nodeA = new SipRingingHandoff(
                new MsisdnNormalizer(config),
                new RealmRouter(config),
                metricsA,
                event -> {
                    nodeAEvents.add(event.callId());
                    pool.execute(() -> processorA.process(event));
                });
        SipRingingHandoff nodeB = new SipRingingHandoff(
                new MsisdnNormalizer(config),
                new RealmRouter(config),
                metricsB,
                event -> {
                    nodeBEvents.add(event.callId());
                    pool.execute(() -> processorB.process(event));
                });

        assertTrue(nodeA.handoff(CALL_ID, TO, null, FROM));
        assertTrue(nodeB.handoff(CALL_ID, TO, null, FROM));

        assertEquals(List.of(CALL_ID), nodeAEvents);
        assertEquals(List.of(CALL_ID), nodeBEvents);
        assertEquals(1, pushA.all().size());
        assertEquals(1, pushB.all().size());
    }
}
