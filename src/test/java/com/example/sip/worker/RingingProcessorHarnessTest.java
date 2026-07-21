package com.example.sip.worker;

import com.example.sip.cache.TokenCache;
import com.example.sip.config.GatewayConfig;
import com.example.sip.harness.FakeShClient;
import com.example.sip.harness.RecordingPushClient;
import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.push.PushClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 harness smoke: processor with FakeShClient + RecordingPushClient.
 */
class RingingProcessorHarnessTest {

    private GatewayConfig config;
    private GatewayMetrics metrics;
    private AsyncWorkerPool cleanupPool;
    private TokenCache cache;
    private FakeShClient shClient;
    private RecordingPushClient apns;
    private RecordingPushClient fcm;

    @BeforeEach
    void setUp() {
        config = GatewayConfig.builder()
                .cacheMaxEntries(100)
                .cacheTtl(Duration.ofSeconds(300))
                .cacheIdleEvict(Duration.ofSeconds(120))
                .workerCorePoolSize(2)
                .workerMaxPoolSize(4)
                .workerQueueCapacity(16)
                .build();
        metrics = new GatewayMetrics();
        cleanupPool = AsyncWorkerPool.sameThread(config, metrics);
        cache = new TokenCache(config);
        shClient = new FakeShClient()
                .withToken(
                        RingingEventFixtures.CALLEE_E164,
                        new PushTokenRecord("tok-apns", PushPlatform.APNS, 5));
        apns = new RecordingPushClient().alwaysSucceed();
        fcm = new RecordingPushClient().alwaysSucceed();
    }

    @AfterEach
    void tearDown() {
        cleanupPool.close();
        shClient.close();
    }

    @Test
    void cacheMissPerformsUdrThenRecordsApnsPushWithPlatform() {
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(1, shClient.udrCount());
        assertEquals("APNS", apns.lastPlatform());
        assertTrue(apns.lastJsonBody().contains("\"platform\":\"APNS\""));
        assertEquals(1, metrics.hssCacheMisses());
        assertEquals(0, fcm.all().size());
    }

    @Test
    void cacheHitSkipsUdr() {
        cache.put(
                RingingEventFixtures.CALLEE_E164,
                new PushTokenRecord("cached", PushPlatform.FCM, 1));
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(0, shClient.udrCount());
        assertEquals("FCM", fcm.lastPlatform());
        assertEquals(1, metrics.hssCacheHits());
    }

    @Test
    void invalidTokenTriggersPurge() {
        apns = new RecordingPushClient()
                .enqueue(PushClient.PushResult.failure(410, "apns_410", true));
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(1, shClient.purCalls().size());
        assertTrue(cache.get(RingingEventFixtures.CALLEE_E164).isEmpty());
        assertEquals(1, metrics.pushTokenPurges());
    }
}
