package com.example.sip.model;

/**
 * Push platform stored in HSS Sh repository data.
 */
public enum PushPlatform {
    APNS,
    FCM;

    public static PushPlatform fromHssValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Platform value is required");
        }
        return PushPlatform.valueOf(raw.trim().toUpperCase());
    }
}
