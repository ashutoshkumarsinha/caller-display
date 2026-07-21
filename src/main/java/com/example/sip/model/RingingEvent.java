package com.example.sip.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Extracted SIP ringing context handed off from the SIP thread to workers.
 */
public final class RingingEvent {

    private final String callId;
    private final String callerDisplay;
    private final String callerMsisdn;
    private final String calleeMsisdn;
    private final String calleeSipUri;
    private final String destinationRealm;
    private final String eventId;

    public RingingEvent(
            String callId,
            String callerDisplay,
            String callerMsisdn,
            String calleeMsisdn,
            String calleeSipUri,
            String destinationRealm,
            String eventId) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.callerDisplay = Objects.requireNonNull(callerDisplay, "callerDisplay");
        this.callerMsisdn = callerMsisdn;
        this.calleeMsisdn = Objects.requireNonNull(calleeMsisdn, "calleeMsisdn");
        this.calleeSipUri = calleeSipUri;
        this.destinationRealm = Objects.requireNonNull(destinationRealm, "destinationRealm");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
    }

    public String callId() {
        return callId;
    }

    public String callerDisplay() {
        return callerDisplay;
    }

    public Optional<String> callerMsisdn() {
        return Optional.ofNullable(callerMsisdn);
    }

    public String calleeMsisdn() {
        return calleeMsisdn;
    }

    public Optional<String> calleeSipUri() {
        return Optional.ofNullable(calleeSipUri);
    }

    public String destinationRealm() {
        return destinationRealm;
    }

    public String eventId() {
        return eventId;
    }
}
