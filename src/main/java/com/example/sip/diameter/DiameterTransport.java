package com.example.sip.diameter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Diameter stack I/O boundary. Production uses jDiameter; tests use {@code MockHssDiameterTransport}.
 */
public interface DiameterTransport extends AutoCloseable {

    void start();

    /**
     * @return true if at least one peer is considered OPEN for the destination realm
     */
    boolean hasOpenPeerForRealm(String destinationRealm);

    CompletableFuture<ShAnswer> sendUdr(ShUdrRequest request, Duration timeout);

    CompletableFuture<ShAnswer> sendPur(ShPurRequest request, Duration timeout);

    @Override
    void close();
}
