package com.example.sip.diameter;

import com.example.sip.model.PushTokenRecord;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Diameter Sh operations used by the ringing pipeline. Production uses {@link ShClient};
 * tests use harness fakes.
 */
public interface ShClientApi extends AutoCloseable {

    void start();

    CompletableFuture<Optional<PushTokenRecord>> userDataRequest(
            String normalizedCalleeMsisdn,
            String destinationRealm);

    CompletableFuture<Void> purgeToken(
            String normalizedCalleeMsisdn,
            String destinationRealm,
            long nextSequenceNumber);

    @Override
    void close();
}
