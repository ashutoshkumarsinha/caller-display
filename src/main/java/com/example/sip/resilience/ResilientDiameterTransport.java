package com.example.sip.resilience;

import com.example.sip.diameter.DiameterTransport;
import com.example.sip.diameter.ShAnswer;
import com.example.sip.diameter.ShPurRequest;
import com.example.sip.diameter.ShUdrRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Diameter transport decorator: HSS circuit breaker fail-fast (T3.1).
 *
 * <p>{@link ShAnswer.Outcome#USER_UNKNOWN} does not trip the breaker (valid HSS response).
 */
public final class ResilientDiameterTransport implements DiameterTransport {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientDiameterTransport.class);

    private final DiameterTransport delegate;
    private final CircuitBreaker breaker;

    public ResilientDiameterTransport(DiameterTransport delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public boolean hasOpenPeerForRealm(String destinationRealm) {
        return delegate.hasOpenPeerForRealm(destinationRealm);
    }

    @Override
    public CompletableFuture<ShAnswer> sendUdr(ShUdrRequest request, Duration timeout) {
        if (!breaker.tryAcquirePermission()) {
            LOG.warn("HSS circuit open; skipping UDR realm={}", request.destinationRealm());
            return CompletableFuture.completedFuture(ShAnswer.circuitOpen());
        }
        long start = System.nanoTime();
        return delegate.sendUdr(request, timeout).whenComplete((answer, error) ->
                record(start, answer, error));
    }

    @Override
    public CompletableFuture<ShAnswer> sendPur(ShPurRequest request, Duration timeout) {
        if (!breaker.tryAcquirePermission()) {
            return CompletableFuture.completedFuture(ShAnswer.circuitOpen());
        }
        long start = System.nanoTime();
        return delegate.sendPur(request, timeout).whenComplete((answer, error) ->
                record(start, answer, error));
    }

    private void record(long startNanos, ShAnswer answer, Throwable error) {
        long duration = System.nanoTime() - startNanos;
        if (error != null) {
            breaker.onError(duration, TimeUnit.NANOSECONDS, error);
            return;
        }
        if (answer != null && isBreakerFailure(answer)) {
            breaker.onError(
                    duration,
                    TimeUnit.NANOSECONDS,
                    new RuntimeException(answer.failureCause()));
            return;
        }
        breaker.onSuccess(duration, TimeUnit.NANOSECONDS);
    }

    static boolean isBreakerFailure(ShAnswer answer) {
        return switch (answer.outcome()) {
            case TIMEOUT, UNABLE_TO_DELIVER, REALM_NOT_SERVED, NO_PEER, ERROR, CIRCUIT_OPEN -> true;
            case SUCCESS, USER_UNKNOWN -> false;
        };
    }

    @Override
    public void close() {
        delegate.close();
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public DiameterTransport delegate() {
        return delegate;
    }
}
