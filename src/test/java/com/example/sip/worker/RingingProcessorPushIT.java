package com.example.sip.worker;

import com.example.sip.cache.TokenCache;
import com.example.sip.config.GatewayConfig;
import com.example.sip.harness.FakeShClient;
import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.push.ApnsClient;
import com.example.sip.push.BearerTokenProvider;
import com.example.sip.push.FcmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.4–T2.8 — processor + WireMock push + FakeShClient integration.
 */
class RingingProcessorPushIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private GatewayConfig config;
    private GatewayMetrics metrics;
    private AsyncWorkerPool cleanupPool;
    private TokenCache cache;
    private FakeShClient shClient;
    private ObjectMapper mapper;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        metrics = new GatewayMetrics();
        config = GatewayConfig.builder()
                .apnsUrl(wireMock.baseUrl())
                .fcmUrl(wireMock.baseUrl() + "/v1/projects/demo/messages:send")
                .apnsBearer("apns-token")
                .fcmBearer("fcm-token")
                .apnsPushType("voip")
                .apnsPriority("10")
                .apnsTopic("com.example.app.voip")
                .pushHttpTimeout(Duration.ofSeconds(2))
                .cacheMaxEntries(100)
                .cacheTtl(Duration.ofSeconds(300))
                .cacheIdleEvict(Duration.ofSeconds(120))
                .workerCorePoolSize(2)
                .workerMaxPoolSize(4)
                .workerQueueCapacity(16)
                .build();
        cleanupPool = AsyncWorkerPool.sameThread(config, metrics);
        cache = new TokenCache(config);
        shClient = new FakeShClient().withToken(
                RingingEventFixtures.CALLEE_E164,
                new PushTokenRecord("device-token-1", PushPlatform.APNS, 7));
    }

    @AfterEach
    void tearDown() {
        cleanupPool.close();
        shClient.close();
    }

    @Test
    void wireMock200IncrementsPushSuccess() {
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        ApnsClient apns = new ApnsClient(config, httpClient, mapper);
        FcmClient fcm = new FcmClient(config, httpClient, mapper);
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(1, shClient.udrCount());
        assertEquals(1L, metrics.pushSuccessCount("APNS"));
        assertEquals(0L, metrics.pushTokenPurges());
    }

    @Test
    void apns410InvalidatesCacheAndSchedulesPur() {
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(410).withBody("{\"reason\":\"BadDeviceToken\"}")));

        ApnsClient apns = new ApnsClient(config, httpClient, mapper);
        FcmClient fcm = new FcmClient(config, httpClient, mapper);
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(1, shClient.purCalls().size());
        assertEquals(8L, shClient.purCalls().get(0).sequenceNumber());
        assertTrue(cache.get(RingingEventFixtures.CALLEE_E164).isEmpty());
        assertEquals(1L, metrics.pushTokenPurges());
    }

    @Test
    void fcmUnregisteredTriggersPurge() {
        shClient = new FakeShClient().withToken(
                RingingEventFixtures.CALLEE_E164,
                new PushTokenRecord("fcm-device", PushPlatform.FCM, 3));
        wireMock.stubFor(post(urlEqualTo("/v1/projects/demo/messages:send"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody("{\"error\":{\"status\":\"UNREGISTERED\"}}")));

        ApnsClient apns = new ApnsClient(config, httpClient, mapper);
        FcmClient fcm = new FcmClient(config, httpClient, mapper);
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(1, shClient.purCalls().size());
        assertEquals(1L, metrics.pushTokenPurges());
    }

    @Test
    void authFailureRefreshesWithoutPurge() {
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(401).withBody("{}")));

        BearerTokenProvider auth = new BearerTokenProvider("apns-token", () -> null);
        ApnsClient apns = new ApnsClient(config, httpClient, mapper, auth);
        FcmClient fcm = new FcmClient(config, httpClient, mapper);
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(0, shClient.purCalls().size());
        assertEquals(0L, metrics.pushTokenPurges());
        assertEquals(1, auth.refreshCount());
        assertTrue(cache.get(RingingEventFixtures.CALLEE_E164).isPresent());
    }

    @Test
    void cacheHitSkipsUdr() {
        cache.put(
                RingingEventFixtures.CALLEE_E164,
                new PushTokenRecord("device-token-1", PushPlatform.APNS, 1));
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        ApnsClient apns = new ApnsClient(config, httpClient, mapper);
        FcmClient fcm = new FcmClient(config, httpClient, mapper);
        RingingProcessor processor = new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(0, shClient.udrCount());
        assertEquals(1L, metrics.hssCacheHits());
        assertEquals(1L, metrics.pushSuccessCount("APNS"));
    }
}
