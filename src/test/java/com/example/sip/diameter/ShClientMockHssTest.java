package com.example.sip.diameter;

import com.example.sip.config.GatewayConfig;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.harness.RingingEventFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.5, T1.6, T1.8 — ShClient over MockHssDiameterTransport.
 */
class ShClientMockHssTest {

    private static final String REALM_A = "ims.mnc001.mcc001.3gppnetwork.org";
    private static final String REALM_B = "ims.mnc010.mcc234.3gppnetwork.org";

    private GatewayConfig config;
    private GatewayMetrics metrics;
    private MockHssDiameterTransport transport;
    private ShClient shClient;

    @BeforeEach
    void setUp() {
        config = GatewayConfig.builder()
                .omitDestinationHost(true)
                .serviceIndication("PushNotificationAppV1")
                .diameterMessageTimeout(Duration.ofMillis(500))
                .diameterTransport("mock")
                .build();
        metrics = new GatewayMetrics();
        transport = new MockHssDiameterTransport()
                .withOpenPeer(REALM_A, "aaas://hss1." + REALM_A + ":5658")
                .withOpenPeer(REALM_B, "aaas://hss1." + REALM_B + ":5658")
                .withToken(
                        REALM_A,
                        RingingEventFixtures.CALLEE_E164,
                        new PushTokenRecord("tok-a", PushPlatform.APNS, 9));
        shClient = new ShClient(config, transport, metrics);
        shClient.start();
    }

    @AfterEach
    void tearDown() {
        shClient.close();
    }

    @Test
    void udrReturnsTokenForRealm() throws Exception {
        Optional<PushTokenRecord> record = shClient
                .userDataRequest(RingingEventFixtures.CALLEE_E164, REALM_A)
                .get(2, TimeUnit.SECONDS);

        assertTrue(record.isPresent());
        assertEquals("tok-a", record.get().deviceToken());
        assertEquals(1, transport.deliveredUdrs().size());
        assertEquals(REALM_A, transport.deliveredUdrs().get(0).request().destinationRealm());
        assertTrue(transport.deliveredUdrs().get(0).request().omitDestinationHost());
        assertEquals("tel:" + RingingEventFixtures.CALLEE_E164,
                transport.deliveredUdrs().get(0).request().userIdentityTel());
    }

    @Test
    void userUnknownReturnsEmptyWithoutThrowing() throws Exception {
        Optional<PushTokenRecord> record = shClient
                .userDataRequest("+19999999999", REALM_A)
                .get(2, TimeUnit.SECONDS);

        assertTrue(record.isEmpty());
        assertEquals(1L, metrics.hssFailureCount(REALM_A, "user_unknown"));
    }

    @Test
    void timeoutReturnsEmpty() throws Exception {
        transport.withDelay(Duration.ofSeconds(2));
        GatewayConfig shortTimeout = GatewayConfig.builder()
                .omitDestinationHost(true)
                .diameterMessageTimeout(Duration.ofMillis(50))
                .build();
        ShClient client = new ShClient(shortTimeout, transport, metrics);
        client.start();

        Optional<PushTokenRecord> record = client
                .userDataRequest(RingingEventFixtures.CALLEE_E164, REALM_A)
                .get(3, TimeUnit.SECONDS);

        assertTrue(record.isEmpty());
        assertEquals(1L, metrics.hssFailureCount(REALM_A, "timeout"));
        client.close();
    }

    @Test
    void noOpenPeerIncrementsRealmNoPeerMetric() throws Exception {
        String orphanRealm = "ims.mnc099.mcc999.3gppnetwork.org";

        Optional<PushTokenRecord> record = shClient
                .userDataRequest(RingingEventFixtures.CALLEE_E164, orphanRealm)
                .get(2, TimeUnit.SECONDS);

        assertTrue(record.isEmpty());
        assertEquals(1L, metrics.realmNoPeerCount(orphanRealm));
        assertEquals(0, transport.deliveredUdrs().size());
    }

    @Test
    void requestForRealmANeverLandsOnRealmBPeer() throws Exception {
        shClient.userDataRequest(RingingEventFixtures.CALLEE_E164, REALM_A)
                .get(2, TimeUnit.SECONDS);

        assertEquals(1, transport.deliveredUdrs().size());
        assertTrue(transport.deliveredUdrs().get(0).peerHost().contains(REALM_A));
        assertTrue(!transport.deliveredUdrs().get(0).peerHost().contains("mnc010"));
    }

    @Test
    void purClearsTokenInMockHss() throws Exception {
        shClient.purgeToken(RingingEventFixtures.CALLEE_E164, REALM_A, 10)
                .get(2, TimeUnit.SECONDS);

        assertEquals(1, transport.deliveredPurs().size());
        assertEquals(10L, transport.deliveredPurs().get(0).request().sequenceNumber());
        assertTrue(shClient.userDataRequest(RingingEventFixtures.CALLEE_E164, REALM_A)
                .get(2, TimeUnit.SECONDS)
                .isEmpty());
    }

    @Test
    void userUnknownDoesNotDeliverSuccessfulToken() throws Exception {
        Optional<PushTokenRecord> record = shClient
                .userDataRequest("+15551212", REALM_A)
                .get(2, TimeUnit.SECONDS);

        assertTrue(record.isEmpty());
        assertEquals(1L, metrics.hssFailureCount(REALM_A, "user_unknown"));
    }
}
