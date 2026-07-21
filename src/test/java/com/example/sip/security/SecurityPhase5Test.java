package com.example.sip.security;

import com.example.sip.config.GatewayConfig;
import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.harness.TestConfigs;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.ApnsClient;
import com.example.sip.push.BearerTokenProvider;
import com.example.sip.push.FcmClient;
import com.example.sip.push.PushClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.1–T5.3 — fail-closed credentials, vault MP Config resolution, pre-expiry OAuth refresh.
 */
class SecurityPhase5Test {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private ObjectMapper mapper;
    private HttpClient httpClient;
    private RingingEvent event;
    private PushTokenRecord apnsToken;
    private PushTokenRecord fcmToken;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        event = RingingEventFixtures.ringingE164();
        apnsToken = new PushTokenRecord("device-token-1", PushPlatform.APNS, 5);
        fcmToken = new PushTokenRecord("fcm-device-1", PushPlatform.FCM, 5);
        wireMock.resetAll();
    }

    @Test
    void missingApnsBearerFailsClosedWithoutHttp() throws Exception {
        GatewayConfig config = GatewayConfig.builder()
                .apnsUrl(wireMock.baseUrl())
                .apnsBearer("")
                .pushHttpTimeout(Duration.ofSeconds(2))
                .build();
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        ApnsClient client = new ApnsClient(config, httpClient, mapper, BearerTokenProvider.of(""));
        PushClient.PushResult result = client.send(event, apnsToken).get(3, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertEquals("apns_missing_bearer", result.errorCode());
        assertFalse(result.tokenInvalid());
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/3/device/device-token-1")));
    }

    @Test
    void missingFcmBearerFailsClosedWithoutHttp() throws Exception {
        GatewayConfig config = GatewayConfig.builder()
                .fcmUrl(wireMock.baseUrl() + "/v1/projects/demo/messages:send")
                .fcmBearer("")
                .pushHttpTimeout(Duration.ofSeconds(2))
                .build();
        wireMock.stubFor(post(urlEqualTo("/v1/projects/demo/messages:send"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        FcmClient client = new FcmClient(config, httpClient, mapper, BearerTokenProvider.of(""));
        PushClient.PushResult result = client.send(event, fcmToken).get(3, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertEquals("fcm_missing_bearer", result.errorCode());
        assertFalse(result.tokenInvalid());
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/projects/demo/messages:send")));
    }

    @Test
    void resolvesBearerFromVaultMpConfigSourceNotHardcodedWarLiteral() {
        Config mp = TestConfigs.fromMap(Map.of(
                "vault.apns.bearer", "secret-from-vault-apns",
                "vault.fcm.bearer", "secret-from-vault-fcm",
                "gateway.push.apns.bearer", "${vault.apns.bearer}",
                "gateway.push.fcm.bearer", "${vault.fcm.bearer}"));

        GatewayConfig config = GatewayConfig.fromConfig(mp);

        assertEquals("secret-from-vault-apns", config.apnsBearer());
        assertEquals("secret-from-vault-fcm", config.fcmBearer());
    }

    @Test
    void resolvesBearerDirectlyFromVaultKeysWhenGatewayKeyAbsent() {
        Config mp = TestConfigs.fromMap(Map.of(
                "vault.apns.bearer", "direct-vault-apns",
                "vault.fcm.bearer", "direct-vault-fcm"));

        GatewayConfig config = GatewayConfig.fromConfig(mp);

        assertEquals("direct-vault-apns", config.apnsBearer());
        assertEquals("direct-vault-fcm", config.fcmBearer());
    }

    @Test
    void packagedPropertiesResolveSecretsViaPlaceholdersNotLiterals() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/microprofile-config.properties")) {
            assertTrue(in != null, "microprofile-config.properties must be on classpath");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("gateway.push.apns.bearer=${vault.apns.bearer"),
                    "APNS bearer must resolve via vault/env placeholder");
            assertTrue(content.contains("gateway.push.fcm.bearer=${vault.fcm.bearer"),
                    "FCM bearer must resolve via vault/env placeholder");
            assertFalse(content.matches("(?s).*gateway\\.push\\.apns\\.bearer=[A-Za-z0-9._-]{20,}.*"),
                    "APNS bearer must not be a hardcoded literal");
            assertFalse(content.contains("eyJ"), "JWT-looking secrets must not be checked in");
        }
    }

    @Test
    void oauthRefreshScheduledBeforeExpiryWithFakeClock() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-21T12:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        AtomicInteger refreshes = new AtomicInteger();
        OauthBearerTokenProvider provider = new OauthBearerTokenProvider(
                new AccessToken("initial-token", Instant.parse("2026-07-21T12:10:00Z")),
                Duration.ofMinutes(2),
                () -> {
                    refreshes.incrementAndGet();
                    return new AccessToken(
                            "rotated-token-" + refreshes.get(),
                            now.get().plus(Duration.ofMinutes(10)));
                },
                clock);

        assertEquals("initial-token", provider.currentToken());
        assertEquals(0, refreshes.get());

        // Within skew window (expiry - 2m = 12:08); advance to 12:08:30 → proactive refresh.
        now.set(Instant.parse("2026-07-21T12:08:30Z"));
        assertEquals("rotated-token-1", provider.currentToken());
        assertEquals(1, refreshes.get());
        assertEquals(1, provider.refreshCount());
    }

    @Test
    void tokenRefreshSchedulerRunsBeforeExpiry() throws Exception {
        MutableTestClock mutableClock = new MutableTestClock(Instant.parse("2026-07-21T12:00:00Z"));
        AtomicInteger refreshes = new AtomicInteger();

        OauthBearerTokenProvider provider = new OauthBearerTokenProvider(
                new AccessToken("sched-token", Instant.parse("2026-07-21T12:00:05Z")),
                Duration.ofSeconds(2),
                () -> {
                    refreshes.incrementAndGet();
                    return new AccessToken("sched-rotated", mutableClock.instant().plusSeconds(60));
                },
                mutableClock);

        try (TokenRefreshScheduler scheduler = new TokenRefreshScheduler(mutableClock, Duration.ofMillis(50))) {
            scheduler.watch(provider);
            mutableClock.advance(Duration.ofSeconds(4));
            Thread.sleep(200);
        }

        assertTrue(refreshes.get() >= 1, "scheduler should refresh before expiry");
        assertEquals("sched-rotated", provider.currentToken());
    }

    private static final class MutableTestClock extends Clock {
        private Instant instant;

        MutableTestClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
