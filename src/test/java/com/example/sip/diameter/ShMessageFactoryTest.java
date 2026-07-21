package com.example.sip.diameter;

import com.example.sip.config.GatewayConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.1–T1.3, T1.7 — Sh message factory AVP rules.
 */
class ShMessageFactoryTest {

    @Test
    void setsDestinationRealmFromRouterOutput() {
        GatewayConfig config = GatewayConfig.builder().omitDestinationHost(true).build();
        ShMessageFactory factory = new ShMessageFactory(config);

        ShUdrRequest udr = factory.createUdr("+14155552671", "ims.mnc010.mcc234.3gppnetwork.org");

        assertEquals("ims.mnc010.mcc234.3gppnetwork.org", udr.destinationRealm());
    }

    @Test
    void omitsDestinationHostWhenConfigured() {
        GatewayConfig config = GatewayConfig.builder().omitDestinationHost(true).build();
        ShMessageFactory factory = new ShMessageFactory(config);

        ShUdrRequest udr = factory.createUdr("+14155552671", "ims.mnc001.mcc001.3gppnetwork.org");

        assertTrue(udr.omitDestinationHost());
        assertTrue(udr.destinationHost().isEmpty());
    }

    @Test
    void userIdentityIsTelUri() {
        GatewayConfig config = GatewayConfig.builder().build();
        ShMessageFactory factory = new ShMessageFactory(config);

        ShUdrRequest udr = factory.createUdr("+14155552671", "ims.example.org");

        assertEquals("tel:+14155552671", udr.userIdentityTel());
    }

    @Test
    void purClearsTokenAndBumpsSequence() {
        GatewayConfig config = GatewayConfig.builder()
                .serviceIndication("PushNotificationAppV1")
                .omitDestinationHost(true)
                .build();
        ShMessageFactory factory = new ShMessageFactory(config);

        ShPurRequest pur = factory.createTokenPurge("+14155552671", "ims.example.org", 110);

        assertEquals(110L, pur.sequenceNumber());
        assertEquals("tel:+14155552671", pur.userIdentityTel());
        assertTrue(pur.omitDestinationHost());
        assertTrue(pur.userDataXml().contains("<SequenceNumber>110</SequenceNumber>"));
        assertTrue(pur.userDataXml().contains("<DeviceToken></DeviceToken>"));
        assertTrue(pur.userDataXml().contains("PushNotificationAppV1"));
        assertFalse(pur.destinationHost().isPresent());
    }
}
