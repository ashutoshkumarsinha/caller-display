package com.example.enrollment.service;

import com.example.enrollment.config.EnrollmentConfig;
import com.example.enrollment.identity.MsisdnNormalizer;
import com.example.enrollment.metrics.EnrollmentMetrics;
import com.example.enrollment.model.PushPlatform;
import com.example.enrollment.model.PushTokenRecord;
import com.example.enrollment.model.StoredPushToken;
import com.example.enrollment.model.SubscriberId;
import com.example.enrollment.spml.MockSpmlClient;
import com.example.enrollment.spml.SpmlClient;
import com.example.enrollment.spml.SpmlPayloadFactory;
import com.example.enrollment.spml.SpmlResult;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Upsert/clear push tokens via SPML with sequence bump rules.
 */
public final class EnrollmentService {

    private final EnrollmentConfig config;
    private final SpmlClient spml;
    private final EnrollmentMetrics metrics;
    private final MsisdnNormalizer normalizer;

    public EnrollmentService(EnrollmentConfig config, SpmlClient spml, EnrollmentMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.spml = Objects.requireNonNull(spml, "spml");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.normalizer = new MsisdnNormalizer(config);
    }

    public Optional<StoredPushToken> get(String rawMsisdn) {
        String msisdn = normalizer.normalize(rawMsisdn);
        if (msisdn == null) {
            return Optional.empty();
        }
        SubscriberId id = SubscriberId.of(msisdn, config.subscriberIdPrefix());
        if (spml instanceof MockSpmlClient mock) {
            return mock.getToken(id.spmlIdentifier())
                    .map(token -> new StoredPushToken(
                            msisdn,
                            token.platform(),
                            token.deviceToken(),
                            mock.currentSequence(id.spmlIdentifier()),
                            TokenFingerprint.sha256Prefix(token.deviceToken())));
        }
        return Optional.empty();
    }

    public EnrollmentOutcome upsert(String rawMsisdn, String platformRaw, String deviceToken, String appId) {
        String msisdn = normalizer.normalize(rawMsisdn);
        if (msisdn == null) {
            metrics.recordUpsert("unknown", "validation_error");
            return EnrollmentOutcome.badRequest("invalid msisdn");
        }
        if (deviceToken == null || deviceToken.isBlank()) {
            metrics.recordUpsert("unknown", "validation_error");
            return EnrollmentOutcome.badRequest("deviceToken is required");
        }
        if (deviceToken.length() > config.maxDeviceTokenLength()) {
            metrics.recordUpsert("unknown", "validation_error");
            return EnrollmentOutcome.badRequest("deviceToken too long");
        }
        PushPlatform platform;
        try {
            platform = PushPlatform.parse(platformRaw);
        } catch (IllegalArgumentException ex) {
            metrics.recordUpsert("unknown", "validation_error");
            return EnrollmentOutcome.badRequest(ex.getMessage());
        }

        SubscriberId id = SubscriberId.of(msisdn, config.subscriberIdPrefix());
        if (spml instanceof MockSpmlClient mock) {
            Optional<PushTokenRecord> existing = mock.getToken(id.spmlIdentifier());
            if (existing.isPresent()
                    && existing.get().deviceToken().equals(deviceToken)
                    && existing.get().platform() == platform) {
                metrics.recordUpsert(platform.name(), "idempotent");
                return EnrollmentOutcome.noContent();
            }
        }

        long nextSeq = Math.max(1L, spml.currentSequence(id.spmlIdentifier()) + 1);
        String xml = SpmlPayloadFactory.upsertToken(
                config.serviceIndication(), nextSeq, deviceToken, platform);
        long start = System.nanoTime();
        SpmlResult result = spml.upsertPushToken(id.spmlIdentifier(), xml);
        metrics.recordSpmlLatency(Duration.ofNanos(System.nanoTime() - start));
        return mapResult(result, platform.name(), "upsert");
    }

    public EnrollmentOutcome clear(String rawMsisdn) {
        String msisdn = normalizer.normalize(rawMsisdn);
        if (msisdn == null) {
            metrics.recordClear("validation_error");
            return EnrollmentOutcome.badRequest("invalid msisdn");
        }
        SubscriberId id = SubscriberId.of(msisdn, config.subscriberIdPrefix());
        long nextSeq = Math.max(1L, spml.currentSequence(id.spmlIdentifier()) + 1);
        String xml = SpmlPayloadFactory.clearToken(config.serviceIndication(), nextSeq);
        long start = System.nanoTime();
        SpmlResult result = spml.clearPushToken(id.spmlIdentifier(), xml);
        metrics.recordSpmlLatency(Duration.ofNanos(System.nanoTime() - start));
        return mapResult(result, "unknown", "clear");
    }

    private EnrollmentOutcome mapResult(SpmlResult result, String platform, String operation) {
        if (result.success()) {
            if ("clear".equals(operation)) {
                metrics.recordClear("success");
            } else {
                metrics.recordUpsert(platform, "success");
            }
            return EnrollmentOutcome.noContent();
        }
        if ("subscriber_not_found".equals(result.errorCode())) {
            if ("clear".equals(operation)) {
                metrics.recordClear("not_found");
            } else {
                metrics.recordUpsert(platform, "not_found");
            }
            return EnrollmentOutcome.notFound(result.detail());
        }
        if ("clear".equals(operation)) {
            metrics.recordClear("spml_error");
        } else {
            metrics.recordUpsert(platform, "spml_error");
        }
        return EnrollmentOutcome.badGateway(result.detail());
    }

    public record EnrollmentOutcome(int status, String body, String errorCode) {
        public static EnrollmentOutcome noContent() {
            return new EnrollmentOutcome(204, null, null);
        }

        public static EnrollmentOutcome badRequest(String message) {
            return new EnrollmentOutcome(400, message, "validation_error");
        }

        public static EnrollmentOutcome notFound(String message) {
            return new EnrollmentOutcome(404, message, "subscriber_not_found");
        }

        public static EnrollmentOutcome badGateway(String message) {
            return new EnrollmentOutcome(502, message, "spml_upstream");
        }
    }
}
