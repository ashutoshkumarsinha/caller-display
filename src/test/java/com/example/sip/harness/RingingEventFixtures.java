package com.example.sip.harness;

import com.example.sip.model.RingingEvent;

import java.util.UUID;

/**
 * Spec-aligned SIP ringing fixtures for component tests.
 */
public final class RingingEventFixtures {

    public static final String DEFAULT_REALM = "ims.mnc001.mcc001.3gppnetwork.org";
    public static final String CALLEE_E164 = "+14155552671";
    public static final String CALLER_E164 = "+12125559843";

    private RingingEventFixtures() {
    }

    public static RingingEvent ringingE164() {
        return new RingingEvent(
                "c0a80101-13c4-42f1-90a2-e6b7c8d9e0f1@ims.mnc001.mcc001.org",
                CALLER_E164,
                CALLER_E164,
                CALLEE_E164,
                "sip:" + CALLEE_E164 + "@" + DEFAULT_REALM,
                DEFAULT_REALM,
                UUID.randomUUID().toString());
    }

    public static RingingEvent ringingAnonymousCaller() {
        return new RingingEvent(
                "anon-call-id@ims.example.org",
                "Anonymous",
                null,
                CALLEE_E164,
                "sip:" + CALLEE_E164 + "@" + DEFAULT_REALM,
                DEFAULT_REALM,
                UUID.randomUUID().toString());
    }

    public static RingingEvent ringingWithPCalledPartyId(String destinationRealm) {
        return new RingingEvent(
                "fwd-call-id@ims.example.org",
                CALLER_E164,
                CALLER_E164,
                CALLEE_E164,
                "sip:" + CALLEE_E164 + "@" + destinationRealm,
                destinationRealm,
                UUID.randomUUID().toString());
    }

    public static String toHeaderE164() {
        return "To: <sip:" + CALLEE_E164 + "@" + DEFAULT_REALM + ";user=phone>";
    }

    public static String fromHeaderE164() {
        return "From: \"Wireless Caller\" <tel:" + CALLER_E164 + ">";
    }

    public static String fromHeaderAnonymous() {
        return "From: \"Anonymous\" <sip:anonymous@anonymous.invalid>";
    }

    public static String pCalledPartyId() {
        return "P-Called-Party-ID: <sip:" + CALLEE_E164 + "@" + DEFAULT_REALM + ">";
    }
}
