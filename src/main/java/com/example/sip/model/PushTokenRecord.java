package com.example.sip.model;

import java.util.Objects;

/**
 * Device push token and platform resolved from HSS (or cache).
 */
public final class PushTokenRecord {

    private final String deviceToken;
    private final PushPlatform platform;
    private final long sequenceNumber;

    public PushTokenRecord(String deviceToken, PushPlatform platform, long sequenceNumber) {
        this.deviceToken = Objects.requireNonNull(deviceToken, "deviceToken");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.sequenceNumber = sequenceNumber;
    }

    public String deviceToken() {
        return deviceToken;
    }

    public PushPlatform platform() {
        return platform;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }
}
