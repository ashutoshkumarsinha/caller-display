package com.example.sip.config;

import com.example.sip.harness.TestConfigs;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0.1 — GatewayConfig loads from MicroProfile Config.
 */
class GatewayConfigMpTest {

    @Test
    void loadsDefaultDestinationRealmFromMpConfig() {
        Config mp = TestConfigs.fromMap(Map.of(
                "gateway.diameter.default-destination-realm", "ims.test.example.org",
                "gateway.msisdn.default-country-code", "44"));

        GatewayConfig config = GatewayConfig.fromConfig(mp);

        assertEquals("ims.test.example.org", config.defaultDestinationRealm());
        assertEquals("44", config.defaultCountryCode());
    }

    @Test
    void loadsRealmPrefixMapAndOmitDestinationHostFromMpConfig() {
        Config mp = TestConfigs.fromProperties("""
                gateway.diameter.omit-destination-host=true
                gateway.diameter.realm-prefix-map=+1415:ims.mnc001.mcc001.3gppnetwork.org,+447:ims.mnc010.mcc234.3gppnetwork.org
                gateway.push.apns.push-type=voip
                """);

        GatewayConfig config = GatewayConfig.fromConfig(mp);

        assertTrue(config.omitDestinationHost());
        assertEquals("voip", config.apnsPushType());
        assertEquals(
                "ims.mnc001.mcc001.3gppnetwork.org",
                config.realmPrefixMap().get("+1415"));
        assertEquals(
                "ims.mnc010.mcc234.3gppnetwork.org",
                config.realmPrefixMap().get("+447"));
    }

    @Test
    void usesBuiltInDefaultsWhenKeysAbsent() {
        GatewayConfig config = GatewayConfig.fromConfig(TestConfigs.fromMap(Map.of()));

        assertEquals("ims.mnc001.mcc001.3gppnetwork.org", config.defaultDestinationRealm());
        assertEquals("1", config.defaultCountryCode());
        assertTrue(config.omitDestinationHost());
    }
}
