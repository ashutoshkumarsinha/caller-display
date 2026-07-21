package com.example.sip.diameter;

import com.example.sip.config.GatewayConfig;

/**
 * Builds Sh UDR/PUR models per realm-routing and AVP rules (unit-testable seam).
 */
public final class ShMessageFactory {

    private final GatewayConfig config;

    public ShMessageFactory(GatewayConfig config) {
        this.config = config;
    }

    public ShUdrRequest createUdr(String normalizedCalleeMsisdn, String destinationRealm) {
        return new ShUdrRequest(
                destinationRealm,
                toTelUri(normalizedCalleeMsisdn),
                config.serviceIndication(),
                config.omitDestinationHost(),
                null);
    }

    public ShPurRequest createTokenPurge(
            String normalizedCalleeMsisdn,
            String destinationRealm,
            long nextSequenceNumber) {
        String xml = ShUserDataXml.clearToken(config.serviceIndication(), nextSequenceNumber);
        return new ShPurRequest(
                destinationRealm,
                toTelUri(normalizedCalleeMsisdn),
                config.serviceIndication(),
                nextSequenceNumber,
                xml,
                config.omitDestinationHost(),
                null);
    }

    static String toTelUri(String normalizedMsisdn) {
        if (normalizedMsisdn == null || normalizedMsisdn.isBlank()) {
            throw new IllegalArgumentException("MSISDN required");
        }
        String trimmed = normalizedMsisdn.trim();
        if (trimmed.startsWith("tel:")) {
            return trimmed;
        }
        return "tel:" + trimmed;
    }
}
