package com.example.enrollment.spml;

/**
 * Provisions HSS RepositoryData via SPML (SOAP 2.0 default).
 */
public interface SpmlClient {

    SpmlResult upsertPushToken(String subscriberIdentifier, String repositoryXml);

    SpmlResult clearPushToken(String subscriberIdentifier, String repositoryXml);

    /** Returns current sequence for subscriber, or empty if unknown. */
    long currentSequence(String subscriberIdentifier);
}
