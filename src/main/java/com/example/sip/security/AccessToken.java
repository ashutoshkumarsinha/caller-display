package com.example.sip.security;

import java.time.Instant;
import java.util.Objects;

/**
 * OAuth / vault access token with absolute expiry.
 */
public record AccessToken(String value, Instant expiresAt) {

    public AccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isBlank() {
        return value.isBlank();
    }

    public static AccessToken of(String value, Instant expiresAt) {
        return new AccessToken(value == null ? "" : value, expiresAt);
    }
}
