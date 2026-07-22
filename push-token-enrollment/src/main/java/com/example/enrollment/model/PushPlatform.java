package com.example.enrollment.model;

/**
 * Push platform stored in HSS RepositoryData.
 */
public enum PushPlatform {
    APNS,
    FCM;

    public static PushPlatform parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("platform is required");
        }
        try {
            return PushPlatform.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("platform must be APNS or FCM");
        }
    }
}
