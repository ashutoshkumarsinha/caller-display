package com.example.sip.resilience;

import com.example.sip.config.GatewayConfig;
import com.example.sip.diameter.MockHssDiameterTransport;
import com.example.sip.diameter.ShAnswer;
import com.example.sip.diameter.ShClient;
import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.PushClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.1–T3.3, T3.5 — circuit breakers, rate limit, retry rules.
 */
class ResiliencePhase3Test {

    private static final String REALM = RingingEventFixtures.DEFAULT_REALM;

    private GatewayMetrics metrics;
    private GatewayResilience resilience;

    @BeforeEach
    void setUp() {
        metrics = new GatewayMetrics();
        resilience = GatewayResilience.forTests(2);
    }

    @Test
    void hssBreakerOpensAndFailsFastWithoutCallingDiameter() throws Exception {
        MockHssDiameterTransport mock = new MockHssDiameterTransport()
                .withOpenPeer(REALM, "aaas://hss1." + REALM + ":5658")
                .withForcedResult(RingingEventFixtures.CALLEE_E164, 3002L); // UNABLE_TO_DELIVER
        ResilientDiameterTransport transport =
                new ResilientDiameterTransport(mock, resilience.hssBreaker());
        GatewayConfig config = GatewayConfig.builder()
                .omitDestinationHost(true)
                .diameterMessageTimeout(Duration.ofMillis(500))
                .build();
        ShClient shClient = new ShClient(config, transport, metrics);
        shClient.start();

        for (int i = 0; i < 4; i++) {
            shClient.userDataRequest(RingingEventFixtures.CALLEE_E164, REALM)
                    .get(2, TimeUnit.SECONDS);
        }
        assertEquals(CircuitBreaker.State.OPEN, resilience.hssBreaker().getState());
        int before = mock.deliveredUdrs().size();

        Optional<PushTokenRecord> result = shClient
                .userDataRequest(RingingEventFixtures.CALLEE_E164, REALM)
                .get(2, TimeUnit.SECONDS);

        assertTrue(result.isEmpty());
        assertEquals(before, mock.deliveredUdrs().size());
        assertEquals(1L, metrics.hssFailureCount(REALM, "circuit_open"));
        shClient.close();
    }

    @Test
    void apnsBreakerOpenDoesNotAffectFcm() throws Exception {
        GatewayResilience local = GatewayResilience.forTests(1000);
        AtomicInteger apnsCalls = new AtomicInteger();
        AtomicInteger fcmCalls = new AtomicInteger();

        PushClient apnsDelegate = (event, token) -> {
            apnsCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    PushClient.PushResult.failure(503, "apns_503", false));
        };
        PushClient fcmDelegate = (event, token) -> {
            fcmCalls.incrementAndGet();
            return CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        };

        ResilientPushClient apns = new ResilientPushClient(
                apnsDelegate,
                local.apnsBreaker(),
                local.pushRateLimiter(),
                metrics,
                "APNS",
                0,
                Duration.ofMillis(1));
        ResilientPushClient fcm = new ResilientPushClient(
                fcmDelegate,
                local.fcmBreaker(),
                local.pushRateLimiter(),
                metrics,
                "FCM",
                0,
                Duration.ofMillis(1));

        RingingEvent event = RingingEventFixtures.ringingE164();
        PushTokenRecord apnsToken = new PushTokenRecord("a", PushPlatform.APNS, 1);
        PushTokenRecord fcmToken = new PushTokenRecord("f", PushPlatform.FCM, 1);

        for (int i = 0; i < 4; i++) {
            apns.send(event, apnsToken).get(2, TimeUnit.SECONDS);
        }
        assertEquals(CircuitBreaker.State.OPEN, local.apnsBreaker().getState());
        int apnsBefore = apnsCalls.get();

        PushClient.PushResult apnsBlocked = apns.send(event, apnsToken).get(2, TimeUnit.SECONDS);
        PushClient.PushResult fcmOk = fcm.send(event, fcmToken).get(2, TimeUnit.SECONDS);

        assertEquals("circuit_open", apnsBlocked.errorCode());
        assertEquals(apnsBefore, apnsCalls.get());
        assertTrue(fcmOk.success());
        assertEquals(1, fcmCalls.get());
        assertEquals(CircuitBreaker.State.CLOSED, local.fcmBreaker().getState());
    }

    @Test
    void rateLimiterCapsPushesPerSecond() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PushClient delegate = (event, token) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        };
        ResilientPushClient client = new ResilientPushClient(
                delegate,
                resilience.apnsBreaker(),
                resilience.pushRateLimiter(),
                metrics,
                "APNS",
                0,
                Duration.ofMillis(1));

        RingingEvent event = RingingEventFixtures.ringingE164();
        PushTokenRecord token = new PushTokenRecord("t", PushPlatform.APNS, 1);

        int allowed = 0;
        int limited = 0;
        for (int i = 0; i < 5; i++) {
            PushClient.PushResult result = client.send(event, token).get(1, TimeUnit.SECONDS);
            if (result.success()) {
                allowed++;
            } else if ("rate_limited".equals(result.errorCode())) {
                limited++;
            }
        }

        assertEquals(2, allowed);
        assertEquals(3, limited);
        assertEquals(2, calls.get());
    }

    @Test
    void retriesOn503ButNotOn410() throws Exception {
        AtomicInteger calls503 = new AtomicInteger();
        PushClient flaky = (event, token) -> {
            int n = calls503.incrementAndGet();
            if (n == 1) {
                return CompletableFuture.completedFuture(
                        PushClient.PushResult.failure(503, "apns_503", false));
            }
            return CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        };
        ResilientPushClient withRetry = new ResilientPushClient(
                flaky,
                resilience.apnsBreaker(),
                GatewayResilience.forTests(100).pushRateLimiter(),
                metrics,
                "APNS",
                2,
                Duration.ofMillis(5));

        PushClient.PushResult ok = withRetry
                .send(RingingEventFixtures.ringingE164(), new PushTokenRecord("t", PushPlatform.APNS, 1))
                .get(3, TimeUnit.SECONDS);
        assertTrue(ok.success());
        assertEquals(2, calls503.get());

        AtomicInteger calls410 = new AtomicInteger();
        PushClient gone = (event, token) -> {
            calls410.incrementAndGet();
            return CompletableFuture.completedFuture(
                    PushClient.PushResult.failure(410, "apns_410", true));
        };
        ResilientPushClient noRetry410 = new ResilientPushClient(
                gone,
                GatewayResilience.forTests().apnsBreaker(),
                GatewayResilience.forTests(100).pushRateLimiter(),
                metrics,
                "APNS",
                2,
                Duration.ofMillis(5));

        PushClient.PushResult invalid = noRetry410
                .send(RingingEventFixtures.ringingE164(), new PushTokenRecord("t", PushPlatform.APNS, 1))
                .get(2, TimeUnit.SECONDS);
        assertTrue(invalid.tokenInvalid());
        assertEquals(1, calls410.get());
    }

    @Test
    void userUnknownDoesNotTripHssBreaker() {
        assertTrue(!ResilientDiameterTransport.isBreakerFailure(
                ShAnswer.ofResult(5001L, null, "hss")));
        assertTrue(ResilientDiameterTransport.isBreakerFailure(ShAnswer.timeout()));
        assertTrue(ResilientPushClient.shouldRetry(
                PushClient.PushResult.failure(503, "x", false)));
        assertTrue(!ResilientPushClient.shouldRetry(
                PushClient.PushResult.failure(410, "x", true)));
    }
}
