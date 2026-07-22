package com.example.enrollment.model;

import java.util.Objects;

/**
 * Device push token bound to a subscriber in HSS.
 */
public record PushTokenRecord(String deviceToken, PushPlatform platform) {

    public PushTokenRecord {
        Objects.requireNonNull(deviceToken, "deviceToken");
        Objects.requireNonNull(platform, "platform");
    }
}
