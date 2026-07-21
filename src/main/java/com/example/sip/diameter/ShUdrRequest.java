package com.example.sip.diameter;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Sh User-Data-Request view used by factories and transports.
 */
public final class ShUdrRequest {

    private final String destinationRealm;
    private final String userIdentityTel;
    private final String serviceIndication;
    private final boolean omitDestinationHost;
    private final String destinationHost;

    public ShUdrRequest(
            String destinationRealm,
            String userIdentityTel,
            String serviceIndication,
            boolean omitDestinationHost,
            String destinationHost) {
        this.destinationRealm = Objects.requireNonNull(destinationRealm);
        this.userIdentityTel = Objects.requireNonNull(userIdentityTel);
        this.serviceIndication = Objects.requireNonNull(serviceIndication);
        this.omitDestinationHost = omitDestinationHost;
        this.destinationHost = destinationHost;
        if (!userIdentityTel.startsWith("tel:")) {
            throw new IllegalArgumentException("User-Identity must be tel: URI, got: " + userIdentityTel);
        }
    }

    public String destinationRealm() {
        return destinationRealm;
    }

    public String userIdentityTel() {
        return userIdentityTel;
    }

    public String serviceIndication() {
        return serviceIndication;
    }

    public boolean omitDestinationHost() {
        return omitDestinationHost;
    }

    public Optional<String> destinationHost() {
        return Optional.ofNullable(destinationHost);
    }
}
