package com.example.enrollment.spml;

import com.example.enrollment.config.EnrollmentConfig;

/**
 * Selects mock or SOAP SPML client from config.
 */
public final class SpmlClientFactory {

    private SpmlClientFactory() {
    }

    public static SpmlClient create(EnrollmentConfig config) {
        if ("soap".equalsIgnoreCase(config.spmlTransport())) {
            return new SoapSpml20Client(config);
        }
        return new MockSpmlClient();
    }
}
