package com.example.enrollment.model;

import java.util.Objects;

/**
 * Normalized subscriber identity for SPML provisioning.
 */
public record SubscriberId(String msisdnE164, String spmlIdentifier) {

    public SubscriberId {
        Objects.requireNonNull(msisdnE164, "msisdnE164");
        Objects.requireNonNull(spmlIdentifier, "spmlIdentifier");
    }

    public static SubscriberId of(String msisdnE164, String prefix) {
        String id = prefix + msisdnE164;
        return new SubscriberId(msisdnE164, id);
    }
}
