package com.example.enrollment.model;

import java.util.Objects;

/**
 * In-memory view of stored enrollment state (mock / diagnostics).
 */
public record StoredPushToken(
        String msisdnE164,
        PushPlatform platform,
        String deviceToken,
        long sequenceNumber,
        String tokenFingerprint) {

    public StoredPushToken {
        Objects.requireNonNull(msisdnE164, "msisdnE164");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(deviceToken, "deviceToken");
        Objects.requireNonNull(tokenFingerprint, "tokenFingerprint");
    }
}
