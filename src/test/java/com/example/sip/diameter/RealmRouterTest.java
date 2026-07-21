package com.example.sip.diameter;

import com.example.sip.config.GatewayConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealmRouterTest {

    @Test
    void prefersLongestPrefixMapMatch() {
        GatewayConfig config = GatewayConfig.builder()
                .defaultDestinationRealm("ims.default.example.org")
                .preferSipUriRealm(true)
                .realmPrefixMap(Map.of(
                        "+1", "ims.mnc001.mcc001.3gppnetwork.org",
                        "+1415", "ims.mnc001.mcc001.3gppnetwork.org",
                        "+447", "ims.mnc010.mcc234.3gppnetwork.org"))
                .build();
        RealmRouter router = new RealmRouter(config);

        assertEquals(
                "ims.mnc010.mcc234.3gppnetwork.org",
                router.resolve("+447123456789", Optional.empty()));
        assertEquals(
                "ims.mnc001.mcc001.3gppnetwork.org",
                router.resolve("+14155552671", Optional.empty()));
    }

    @Test
    void usesSipDomainWhenNoPrefixMatch() {
        GatewayConfig config = GatewayConfig.builder()
                .defaultDestinationRealm("ims.default.example.org")
                .preferSipUriRealm(true)
                .realmPrefixMap(Map.of())
                .build();
        RealmRouter router = new RealmRouter(config);

        assertEquals(
                "ims.mnc010.mcc234.3gppnetwork.org",
                router.resolve(
                        "+9995551212",
                        Optional.of("ims.mnc010.mcc234.3gppnetwork.org")));
    }

    @Test
    void fallsBackToDefaultRealm() {
        GatewayConfig config = GatewayConfig.builder()
                .defaultDestinationRealm("ims.default.example.org")
                .preferSipUriRealm(true)
                .build();
        RealmRouter router = new RealmRouter(config);

        assertEquals(
                "ims.default.example.org",
                router.resolve("+9995551212", Optional.empty()));
    }
}
