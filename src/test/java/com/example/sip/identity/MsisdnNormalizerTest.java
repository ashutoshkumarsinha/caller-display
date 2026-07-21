package com.example.sip.identity;

import com.example.sip.config.GatewayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsisdnNormalizerTest {

    private MsisdnNormalizer normalizer;

    @BeforeEach
    void setUp() {
        GatewayConfig config = GatewayConfig.builder()
                .defaultCountryCode("1")
                .stripTrunkPrefix(true)
                .trunkPrefix("0")
                .build();
        normalizer = new MsisdnNormalizer(config);
    }

    @Test
    void extractsE164FromToHeader() {
        String to = "To: <sip:+14155552671@ims.mnc001.mcc001.3gppnetwork.org;user=phone>";
        assertEquals("+14155552671", normalizer.extractMsisdn(to).orElseThrow());
    }

    @Test
    void extractsFromTelUri() {
        String from = "From: \"Wireless Caller\" <tel:+12125559843>";
        assertEquals("+12125559843", normalizer.extractMsisdn(from).orElseThrow());
        assertEquals("+12125559843", normalizer.resolveCallerDisplay(from));
    }

    @Test
    void normalizesNationalTrunkPrefix() {
        GatewayConfig uk = GatewayConfig.builder()
                .defaultCountryCode("44")
                .stripTrunkPrefix(true)
                .trunkPrefix("0")
                .build();
        MsisdnNormalizer ukNorm = new MsisdnNormalizer(uk);
        assertEquals("+447123456789", ukNorm.normalize("07123456789"));
    }

    @Test
    void anonymousCaller() {
        String from = "From: \"Anonymous\" <sip:anonymous@anonymous.invalid>";
        assertEquals("Anonymous", normalizer.resolveCallerDisplay(from));
    }

    @Test
    void extractsDomain() {
        String to = "<sip:+14155552671@ims.mnc001.mcc001.3gppnetwork.org;user=phone>";
        assertTrue(normalizer.extractDomain(to).isPresent());
        assertEquals("ims.mnc001.mcc001.3gppnetwork.org", normalizer.extractDomain(to).orElseThrow());
    }
}
