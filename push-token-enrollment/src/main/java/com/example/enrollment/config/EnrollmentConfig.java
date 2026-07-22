package com.example.enrollment.config;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Objects;

/**
 * MicroProfile Config for the enrollment service.
 */
public final class EnrollmentConfig {

    private final String spmlTransport;
    private final String spmlEndpoint;
    private final int spmlConnectTimeoutMs;
    private final int spmlReadTimeoutMs;
    private final String serviceIndication;
    private final String subscriberIdPrefix;
    private final String defaultCountryCode;
    private final boolean stripTrunkPrefix;
    private final String trunkPrefix;
    private final int maxDeviceTokenLength;
    private final boolean authEnabled;
    private final String staticBearer;
    private final String requiredScope;
    private final boolean allowAdminScope;
    private final int rateLimitPerSecond;

    private EnrollmentConfig(Builder b) {
        this.spmlTransport = b.spmlTransport;
        this.spmlEndpoint = b.spmlEndpoint;
        this.spmlConnectTimeoutMs = b.spmlConnectTimeoutMs;
        this.spmlReadTimeoutMs = b.spmlReadTimeoutMs;
        this.serviceIndication = b.serviceIndication;
        this.subscriberIdPrefix = b.subscriberIdPrefix;
        this.defaultCountryCode = b.defaultCountryCode;
        this.stripTrunkPrefix = b.stripTrunkPrefix;
        this.trunkPrefix = b.trunkPrefix;
        this.maxDeviceTokenLength = b.maxDeviceTokenLength;
        this.authEnabled = b.authEnabled;
        this.staticBearer = b.staticBearer;
        this.requiredScope = b.requiredScope;
        this.allowAdminScope = b.allowAdminScope;
        this.rateLimitPerSecond = b.rateLimitPerSecond;
    }

    public static EnrollmentConfig fromEnvironment() {
        return fromConfig(ConfigProvider.getConfig());
    }

    public static EnrollmentConfig fromConfig(Config config) {
        Objects.requireNonNull(config, "config");
        return builder()
                .spmlTransport(get(config, "enrollment.spml.transport", "mock"))
                .spmlEndpoint(get(config, "enrollment.spml.endpoint", ""))
                .spmlConnectTimeoutMs(Integer.parseInt(get(config, "enrollment.spml.connect-timeout-ms", "2000")))
                .spmlReadTimeoutMs(Integer.parseInt(get(config, "enrollment.spml.read-timeout-ms", "5000")))
                .serviceIndication(get(config, "enrollment.spml.service-indication", "PushNotificationAppV1"))
                .subscriberIdPrefix(get(config, "enrollment.spml.subscriber-id-prefix", "tel:"))
                .defaultCountryCode(get(config, "enrollment.msisdn.default-country-code", "1"))
                .stripTrunkPrefix(Boolean.parseBoolean(get(config, "enrollment.msisdn.strip-trunk-prefix", "true")))
                .trunkPrefix(get(config, "enrollment.msisdn.trunk-prefix", "0"))
                .maxDeviceTokenLength(Integer.parseInt(get(config, "enrollment.msisdn.max-device-token-length", "4096")))
                .authEnabled(Boolean.parseBoolean(get(config, "enrollment.auth.enabled", "true")))
                .staticBearer(get(config, "enrollment.auth.static-bearer", ""))
                .requiredScope(get(config, "enrollment.auth.required-scope", "push.enroll"))
                .allowAdminScope(Boolean.parseBoolean(get(config, "enrollment.auth.allow-admin-scope", "true")))
                .rateLimitPerSecond(Integer.parseInt(get(config, "enrollment.rate-limit.per-second", "50")))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String get(Config config, String key, String defaultValue) {
        return config.getOptionalValue(key, String.class)
                .filter(v -> !v.isBlank())
                .orElse(defaultValue);
    }

    public String spmlTransport() {
        return spmlTransport;
    }

    public String spmlEndpoint() {
        return spmlEndpoint;
    }

    public int spmlConnectTimeoutMs() {
        return spmlConnectTimeoutMs;
    }

    public int spmlReadTimeoutMs() {
        return spmlReadTimeoutMs;
    }

    public String serviceIndication() {
        return serviceIndication;
    }

    public String subscriberIdPrefix() {
        return subscriberIdPrefix;
    }

    public String defaultCountryCode() {
        return defaultCountryCode;
    }

    public boolean stripTrunkPrefix() {
        return stripTrunkPrefix;
    }

    public String trunkPrefix() {
        return trunkPrefix;
    }

    public int maxDeviceTokenLength() {
        return maxDeviceTokenLength;
    }

    public boolean authEnabled() {
        return authEnabled;
    }

    public String staticBearer() {
        return staticBearer;
    }

    public String requiredScope() {
        return requiredScope;
    }

    public boolean allowAdminScope() {
        return allowAdminScope;
    }

    public int rateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public static final class Builder {
        private String spmlTransport = "mock";
        private String spmlEndpoint = "";
        private int spmlConnectTimeoutMs = 2000;
        private int spmlReadTimeoutMs = 5000;
        private String serviceIndication = "PushNotificationAppV1";
        private String subscriberIdPrefix = "tel:";
        private String defaultCountryCode = "1";
        private boolean stripTrunkPrefix = true;
        private String trunkPrefix = "0";
        private int maxDeviceTokenLength = 4096;
        private boolean authEnabled = true;
        private String staticBearer = "";
        private String requiredScope = "push.enroll";
        private boolean allowAdminScope = true;
        private int rateLimitPerSecond = 50;

        public Builder spmlTransport(String value) {
            this.spmlTransport = Objects.requireNonNull(value);
            return this;
        }

        public Builder spmlEndpoint(String value) {
            this.spmlEndpoint = Objects.requireNonNull(value);
            return this;
        }

        public Builder spmlConnectTimeoutMs(int value) {
            this.spmlConnectTimeoutMs = value;
            return this;
        }

        public Builder spmlReadTimeoutMs(int value) {
            this.spmlReadTimeoutMs = value;
            return this;
        }

        public Builder serviceIndication(String value) {
            this.serviceIndication = Objects.requireNonNull(value);
            return this;
        }

        public Builder subscriberIdPrefix(String value) {
            this.subscriberIdPrefix = Objects.requireNonNull(value);
            return this;
        }

        public Builder defaultCountryCode(String value) {
            this.defaultCountryCode = Objects.requireNonNull(value);
            return this;
        }

        public Builder stripTrunkPrefix(boolean value) {
            this.stripTrunkPrefix = value;
            return this;
        }

        public Builder trunkPrefix(String value) {
            this.trunkPrefix = Objects.requireNonNull(value);
            return this;
        }

        public Builder maxDeviceTokenLength(int value) {
            this.maxDeviceTokenLength = value;
            return this;
        }

        public Builder authEnabled(boolean value) {
            this.authEnabled = value;
            return this;
        }

        public Builder staticBearer(String value) {
            this.staticBearer = value == null ? "" : value;
            return this;
        }

        public Builder requiredScope(String value) {
            this.requiredScope = Objects.requireNonNull(value);
            return this;
        }

        public Builder allowAdminScope(boolean value) {
            this.allowAdminScope = value;
            return this;
        }

        public Builder rateLimitPerSecond(int value) {
            this.rateLimitPerSecond = value;
            return this;
        }

        public EnrollmentConfig build() {
            return new EnrollmentConfig(this);
        }
    }
}
