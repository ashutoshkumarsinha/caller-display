package com.example.sip.config;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable gateway settings loaded from MicroProfile Config.
 */
public final class GatewayConfig {

    private final String defaultCountryCode;
    private final boolean stripTrunkPrefix;
    private final String trunkPrefix;
    private final String defaultDestinationRealm;
    private final boolean preferSipUriRealm;
    private final boolean omitDestinationHost;
    private final Map<String, String> realmPrefixMap;
    private final Map<String, String> realmPlmnMap;
    private final String serviceIndication;
    private final Duration diameterMessageTimeout;
    private final String apnsUrl;
    private final String fcmUrl;
    private final String apnsTopic;
    private final String apnsPushType;
    private final String apnsPriority;
    private final Duration pushHttpTimeout;
    private final String apnsBearer;
    private final String fcmBearer;
    private final int cacheMaxEntries;
    private final Duration cacheTtl;
    private final Duration cacheIdleEvict;
    private final Duration cacheEvictorInterval;
    private final int workerCorePoolSize;
    private final int workerMaxPoolSize;
    private final int workerQueueCapacity;
    private final String workerQueueDropPolicy;
    private final int pushRateLimitPerSecond;
    private final float hssFailureRateThreshold;
    private final Duration hssSlidingWindow;
    private final float apnsFailureRateThreshold;
    private final float fcmFailureRateThreshold;

    private GatewayConfig(Builder b) {
        this.defaultCountryCode = b.defaultCountryCode;
        this.stripTrunkPrefix = b.stripTrunkPrefix;
        this.trunkPrefix = b.trunkPrefix;
        this.defaultDestinationRealm = b.defaultDestinationRealm;
        this.preferSipUriRealm = b.preferSipUriRealm;
        this.omitDestinationHost = b.omitDestinationHost;
        this.realmPrefixMap = Collections.unmodifiableMap(new LinkedHashMap<>(b.realmPrefixMap));
        this.realmPlmnMap = Collections.unmodifiableMap(new LinkedHashMap<>(b.realmPlmnMap));
        this.serviceIndication = b.serviceIndication;
        this.diameterMessageTimeout = b.diameterMessageTimeout;
        this.apnsUrl = b.apnsUrl;
        this.fcmUrl = b.fcmUrl;
        this.apnsTopic = b.apnsTopic;
        this.apnsPushType = b.apnsPushType;
        this.apnsPriority = b.apnsPriority;
        this.pushHttpTimeout = b.pushHttpTimeout;
        this.apnsBearer = b.apnsBearer;
        this.fcmBearer = b.fcmBearer;
        this.cacheMaxEntries = b.cacheMaxEntries;
        this.cacheTtl = b.cacheTtl;
        this.cacheIdleEvict = b.cacheIdleEvict;
        this.cacheEvictorInterval = b.cacheEvictorInterval;
        this.workerCorePoolSize = b.workerCorePoolSize;
        this.workerMaxPoolSize = b.workerMaxPoolSize;
        this.workerQueueCapacity = b.workerQueueCapacity;
        this.workerQueueDropPolicy = b.workerQueueDropPolicy;
        this.pushRateLimitPerSecond = b.pushRateLimitPerSecond;
        this.hssFailureRateThreshold = b.hssFailureRateThreshold;
        this.hssSlidingWindow = b.hssSlidingWindow;
        this.apnsFailureRateThreshold = b.apnsFailureRateThreshold;
        this.fcmFailureRateThreshold = b.fcmFailureRateThreshold;
    }

    /**
     * Loads from the current MicroProfile {@link ConfigProvider} (Liberty MP Config at runtime).
     */
    public static GatewayConfig fromEnvironment() {
        return fromConfig(ConfigProvider.getConfig());
    }

    /**
     * Loads from an explicit MicroProfile {@link Config} (preferred in tests).
     */
    public static GatewayConfig fromConfig(Config config) {
        Objects.requireNonNull(config, "config");
        return builder()
                .defaultCountryCode(get(config, "gateway.msisdn.default-country-code", "1"))
                .stripTrunkPrefix(Boolean.parseBoolean(
                        get(config, "gateway.msisdn.strip-trunk-prefix", "true")))
                .trunkPrefix(get(config, "gateway.msisdn.trunk-prefix", "0"))
                .defaultDestinationRealm(get(
                        config,
                        "gateway.diameter.default-destination-realm",
                        "ims.mnc001.mcc001.3gppnetwork.org"))
                .preferSipUriRealm(Boolean.parseBoolean(
                        get(config, "gateway.diameter.prefer-sip-uri-realm", "true")))
                .omitDestinationHost(Boolean.parseBoolean(
                        get(config, "gateway.diameter.omit-destination-host", "true")))
                .realmPrefixMap(parseMap(get(config, "gateway.diameter.realm-prefix-map", "")))
                .realmPlmnMap(parseMap(get(config, "gateway.diameter.realm-plmn-map", "")))
                .serviceIndication(get(config, "gateway.diameter.service-indication", "PushNotificationAppV1"))
                .diameterMessageTimeout(Duration.ofMillis(Long.parseLong(
                        get(config, "gateway.diameter.message-timeout-ms", "1500"))))
                .apnsUrl(get(config, "gateway.push.apns.url", "https://api.push.apple.com"))
                .fcmUrl(get(
                        config,
                        "gateway.push.fcm.url",
                        "https://fcm.googleapis.com/v1/projects/demo/messages:send"))
                .apnsTopic(get(config, "gateway.push.apns.topic", "com.example.app.voip"))
                .apnsPushType(get(config, "gateway.push.apns.push-type", "voip"))
                .apnsPriority(get(config, "gateway.push.apns.priority", "10"))
                .pushHttpTimeout(Duration.ofMillis(Long.parseLong(
                        get(config, "gateway.push.http-timeout-ms", "3000"))))
                .apnsBearer(firstNonBlank(
                        get(config, "gateway.push.apns.bearer", ""),
                        get(config, "APNS_BEARER", "")))
                .fcmBearer(firstNonBlank(
                        get(config, "gateway.push.fcm.bearer", ""),
                        get(config, "FCM_BEARER", "")))
                .cacheMaxEntries(Integer.parseInt(get(config, "gateway.cache.token.max-entries", "100000")))
                .cacheTtl(Duration.ofSeconds(Long.parseLong(
                        get(config, "gateway.cache.token.ttl-seconds", "300"))))
                .cacheIdleEvict(Duration.ofSeconds(Long.parseLong(
                        get(config, "gateway.cache.token.idle-evict-seconds", "120"))))
                .cacheEvictorInterval(Duration.ofSeconds(Long.parseLong(
                        get(config, "gateway.cache.evictor.interval-seconds", "60"))))
                .workerCorePoolSize(Integer.parseInt(get(config, "gateway.worker.core-pool-size", "32")))
                .workerMaxPoolSize(Integer.parseInt(get(config, "gateway.worker.max-pool-size", "128")))
                .workerQueueCapacity(Integer.parseInt(get(config, "gateway.worker.queue-capacity", "2000")))
                .workerQueueDropPolicy(get(config, "gateway.worker.queue-drop-policy", "DISCARD_OLDEST"))
                .pushRateLimitPerSecond(Integer.parseInt(
                        get(config, "gateway.push.rate-limit.per-second", "500")))
                .hssFailureRateThreshold(Float.parseFloat(
                        get(config, "gateway.cb.hss.failure-rate-threshold", "5")))
                .hssSlidingWindow(Duration.ofSeconds(Long.parseLong(
                        get(config, "gateway.cb.hss.sliding-window-seconds", "10"))))
                .apnsFailureRateThreshold(Float.parseFloat(
                        get(config, "gateway.cb.apns.failure-rate-threshold", "10")))
                .fcmFailureRateThreshold(Float.parseFloat(
                        get(config, "gateway.cb.fcm.failure-rate-threshold", "10")))
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

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    static Map<String, String> parseMap(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx <= 0 || idx == trimmed.length() - 1) {
                throw new IllegalArgumentException("Invalid map entry: " + trimmed);
            }
            map.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
        }
        return map;
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

    public String defaultDestinationRealm() {
        return defaultDestinationRealm;
    }

    public boolean preferSipUriRealm() {
        return preferSipUriRealm;
    }

    public boolean omitDestinationHost() {
        return omitDestinationHost;
    }

    public Map<String, String> realmPrefixMap() {
        return realmPrefixMap;
    }

    public Map<String, String> realmPlmnMap() {
        return realmPlmnMap;
    }

    public String serviceIndication() {
        return serviceIndication;
    }

    public Duration diameterMessageTimeout() {
        return diameterMessageTimeout;
    }

    public String apnsUrl() {
        return apnsUrl;
    }

    public String fcmUrl() {
        return fcmUrl;
    }

    public String apnsTopic() {
        return apnsTopic;
    }

    public String apnsPushType() {
        return apnsPushType;
    }

    public String apnsPriority() {
        return apnsPriority;
    }

    public Duration pushHttpTimeout() {
        return pushHttpTimeout;
    }

    public String apnsBearer() {
        return apnsBearer;
    }

    public String fcmBearer() {
        return fcmBearer;
    }

    public int cacheMaxEntries() {
        return cacheMaxEntries;
    }

    public Duration cacheTtl() {
        return cacheTtl;
    }

    public Duration cacheIdleEvict() {
        return cacheIdleEvict;
    }

    public Duration cacheEvictorInterval() {
        return cacheEvictorInterval;
    }

    public int workerCorePoolSize() {
        return workerCorePoolSize;
    }

    public int workerMaxPoolSize() {
        return workerMaxPoolSize;
    }

    public int workerQueueCapacity() {
        return workerQueueCapacity;
    }

    public String workerQueueDropPolicy() {
        return workerQueueDropPolicy;
    }

    public int pushRateLimitPerSecond() {
        return pushRateLimitPerSecond;
    }

    public float hssFailureRateThreshold() {
        return hssFailureRateThreshold;
    }

    public Duration hssSlidingWindow() {
        return hssSlidingWindow;
    }

    public float apnsFailureRateThreshold() {
        return apnsFailureRateThreshold;
    }

    public float fcmFailureRateThreshold() {
        return fcmFailureRateThreshold;
    }

    public static final class Builder {
        private String defaultCountryCode = "1";
        private boolean stripTrunkPrefix = true;
        private String trunkPrefix = "0";
        private String defaultDestinationRealm = "ims.mnc001.mcc001.3gppnetwork.org";
        private boolean preferSipUriRealm = true;
        private boolean omitDestinationHost = true;
        private Map<String, String> realmPrefixMap = Map.of();
        private Map<String, String> realmPlmnMap = Map.of();
        private String serviceIndication = "PushNotificationAppV1";
        private Duration diameterMessageTimeout = Duration.ofMillis(1500);
        private String apnsUrl = "https://api.push.apple.com";
        private String fcmUrl = "https://fcm.googleapis.com/v1/projects/demo/messages:send";
        private String apnsTopic = "com.example.app.voip";
        private String apnsPushType = "voip";
        private String apnsPriority = "10";
        private Duration pushHttpTimeout = Duration.ofSeconds(3);
        private String apnsBearer = "";
        private String fcmBearer = "";
        private int cacheMaxEntries = 100_000;
        private Duration cacheTtl = Duration.ofSeconds(300);
        private Duration cacheIdleEvict = Duration.ofSeconds(120);
        private Duration cacheEvictorInterval = Duration.ofSeconds(60);
        private int workerCorePoolSize = 32;
        private int workerMaxPoolSize = 128;
        private int workerQueueCapacity = 2000;
        private String workerQueueDropPolicy = "DISCARD_OLDEST";
        private int pushRateLimitPerSecond = 500;
        private float hssFailureRateThreshold = 5f;
        private Duration hssSlidingWindow = Duration.ofSeconds(10);
        private float apnsFailureRateThreshold = 10f;
        private float fcmFailureRateThreshold = 10f;

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

        public Builder defaultDestinationRealm(String value) {
            this.defaultDestinationRealm = Objects.requireNonNull(value);
            return this;
        }

        public Builder preferSipUriRealm(boolean value) {
            this.preferSipUriRealm = value;
            return this;
        }

        public Builder omitDestinationHost(boolean value) {
            this.omitDestinationHost = value;
            return this;
        }

        public Builder realmPrefixMap(Map<String, String> value) {
            this.realmPrefixMap = Objects.requireNonNull(value);
            return this;
        }

        public Builder realmPlmnMap(Map<String, String> value) {
            this.realmPlmnMap = Objects.requireNonNull(value);
            return this;
        }

        public Builder serviceIndication(String value) {
            this.serviceIndication = Objects.requireNonNull(value);
            return this;
        }

        public Builder diameterMessageTimeout(Duration value) {
            this.diameterMessageTimeout = Objects.requireNonNull(value);
            return this;
        }

        public Builder apnsUrl(String value) {
            this.apnsUrl = Objects.requireNonNull(value);
            return this;
        }

        public Builder fcmUrl(String value) {
            this.fcmUrl = Objects.requireNonNull(value);
            return this;
        }

        public Builder apnsTopic(String value) {
            this.apnsTopic = Objects.requireNonNull(value);
            return this;
        }

        public Builder apnsPushType(String value) {
            this.apnsPushType = Objects.requireNonNull(value);
            return this;
        }

        public Builder apnsPriority(String value) {
            this.apnsPriority = Objects.requireNonNull(value);
            return this;
        }

        public Builder pushHttpTimeout(Duration value) {
            this.pushHttpTimeout = Objects.requireNonNull(value);
            return this;
        }

        public Builder apnsBearer(String value) {
            this.apnsBearer = Objects.requireNonNull(value);
            return this;
        }

        public Builder fcmBearer(String value) {
            this.fcmBearer = Objects.requireNonNull(value);
            return this;
        }

        public Builder cacheMaxEntries(int value) {
            this.cacheMaxEntries = value;
            return this;
        }

        public Builder cacheTtl(Duration value) {
            this.cacheTtl = Objects.requireNonNull(value);
            return this;
        }

        public Builder cacheIdleEvict(Duration value) {
            this.cacheIdleEvict = Objects.requireNonNull(value);
            return this;
        }

        public Builder cacheEvictorInterval(Duration value) {
            this.cacheEvictorInterval = Objects.requireNonNull(value);
            return this;
        }

        public Builder workerCorePoolSize(int value) {
            this.workerCorePoolSize = value;
            return this;
        }

        public Builder workerMaxPoolSize(int value) {
            this.workerMaxPoolSize = value;
            return this;
        }

        public Builder workerQueueCapacity(int value) {
            this.workerQueueCapacity = value;
            return this;
        }

        public Builder workerQueueDropPolicy(String value) {
            this.workerQueueDropPolicy = Objects.requireNonNull(value);
            return this;
        }

        public Builder pushRateLimitPerSecond(int value) {
            this.pushRateLimitPerSecond = value;
            return this;
        }

        public Builder hssFailureRateThreshold(float value) {
            this.hssFailureRateThreshold = value;
            return this;
        }

        public Builder hssSlidingWindow(Duration value) {
            this.hssSlidingWindow = Objects.requireNonNull(value);
            return this;
        }

        public Builder apnsFailureRateThreshold(float value) {
            this.apnsFailureRateThreshold = value;
            return this;
        }

        public Builder fcmFailureRateThreshold(float value) {
            this.fcmFailureRateThreshold = value;
            return this;
        }

        public GatewayConfig build() {
            return new GatewayConfig(this);
        }
    }
}
