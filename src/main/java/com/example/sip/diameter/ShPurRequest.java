package com.example.sip.diameter;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Sh Profile-Update-Request view for token purge.
 */
public final class ShPurRequest {

    private final String destinationRealm;
    private final String userIdentityTel;
    private final String serviceIndication;
    private final long sequenceNumber;
    private final String userDataXml;
    private final boolean omitDestinationHost;
    private final String destinationHost;

    public ShPurRequest(
            String destinationRealm,
            String userIdentityTel,
            String serviceIndication,
            long sequenceNumber,
            String userDataXml,
            boolean omitDestinationHost,
            String destinationHost) {
        this.destinationRealm = Objects.requireNonNull(destinationRealm);
        this.userIdentityTel = Objects.requireNonNull(userIdentityTel);
        this.serviceIndication = Objects.requireNonNull(serviceIndication);
        this.sequenceNumber = sequenceNumber;
        this.userDataXml = Objects.requireNonNull(userDataXml);
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

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public String userDataXml() {
        return userDataXml;
    }

    public boolean omitDestinationHost() {
        return omitDestinationHost;
    }

    public Optional<String> destinationHost() {
        return Optional.ofNullable(destinationHost);
    }
}
