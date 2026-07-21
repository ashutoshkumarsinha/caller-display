package com.example.sip.diameter;

import java.util.Objects;
import java.util.Optional;

/**
 * Normalized Diameter/Sh answer after transport I/O.
 */
public final class ShAnswer {

    public enum Outcome {
        SUCCESS,
        USER_UNKNOWN,
        UNABLE_TO_DELIVER,
        REALM_NOT_SERVED,
        NO_PEER,
        TIMEOUT,
        CIRCUIT_OPEN,
        ERROR
    }

    private final Outcome outcome;
    private final long resultCode;
    private final String userDataXml;
    private final String originHost;
    private final String detail;

    public ShAnswer(Outcome outcome, long resultCode, String userDataXml, String originHost, String detail) {
        this.outcome = Objects.requireNonNull(outcome);
        this.resultCode = resultCode;
        this.userDataXml = userDataXml;
        this.originHost = originHost;
        this.detail = detail;
    }

    public static ShAnswer success(String userDataXml, String originHost) {
        return new ShAnswer(Outcome.SUCCESS, ShConstants.RESULT_SUCCESS, userDataXml, originHost, null);
    }

    public static ShAnswer ofResult(long resultCode, String userDataXml, String originHost) {
        return new ShAnswer(mapOutcome(resultCode), resultCode, userDataXml, originHost, null);
    }

    public static ShAnswer noPeer(String realm) {
        return new ShAnswer(Outcome.NO_PEER, ShConstants.RESULT_UNABLE_TO_DELIVER, null, null,
                "No OPEN peer for realm " + realm);
    }

    public static ShAnswer timeout() {
        return new ShAnswer(Outcome.TIMEOUT, 0L, null, null, "Diameter message timeout");
    }

    public static ShAnswer circuitOpen() {
        return new ShAnswer(Outcome.CIRCUIT_OPEN, 0L, null, null, "circuit_open");
    }

    public static ShAnswer error(String detail) {
        return new ShAnswer(Outcome.ERROR, 0L, null, null, detail);
    }

    public static Outcome mapOutcome(long resultCode) {
        if (resultCode == ShConstants.RESULT_SUCCESS) {
            return Outcome.SUCCESS;
        }
        if (resultCode == ShConstants.RESULT_USER_UNKNOWN) {
            return Outcome.USER_UNKNOWN;
        }
        if (resultCode == ShConstants.RESULT_UNABLE_TO_DELIVER) {
            return Outcome.UNABLE_TO_DELIVER;
        }
        if (resultCode == ShConstants.RESULT_REALM_NOT_SERVED) {
            return Outcome.REALM_NOT_SERVED;
        }
        return Outcome.ERROR;
    }

    public Outcome outcome() {
        return outcome;
    }

    public long resultCode() {
        return resultCode;
    }

    public Optional<String> userDataXml() {
        return Optional.ofNullable(userDataXml);
    }

    public Optional<String> originHost() {
        return Optional.ofNullable(originHost);
    }

    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }

    public boolean success() {
        return outcome == Outcome.SUCCESS;
    }

    /** Metric cause label for GatewayMetrics.incrementHssFailure. */
    public String failureCause() {
        return switch (outcome) {
            case SUCCESS -> "success";
            case USER_UNKNOWN -> "user_unknown";
            case UNABLE_TO_DELIVER -> "unable_to_deliver";
            case REALM_NOT_SERVED -> "realm_not_served";
            case NO_PEER -> "no_peer";
            case TIMEOUT -> "timeout";
            case CIRCUIT_OPEN -> "circuit_open";
            case ERROR -> "error";
        };
    }
}
