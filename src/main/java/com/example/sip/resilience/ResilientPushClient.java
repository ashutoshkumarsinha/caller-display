package com.example.sip.resilience;

import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.PushClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Push client decorator: per-platform circuit breaker, shared rate limiter, retry on 429/503 only.
 */
public final class ResilientPushClient implements PushClient {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientPushClient.class);

    private final PushClient delegate;
    private final CircuitBreaker breaker;
    private final RateLimiter rateLimiter;
    private final GatewayMetrics metrics;
    private final String platform;
    private final int maxRetries;
    private final Duration retryBaseDelay;

    public ResilientPushClient(
            PushClient delegate,
            CircuitBreaker breaker,
            RateLimiter rateLimiter,
            GatewayMetrics metrics,
            String platform,
            int maxRetries,
            Duration retryBaseDelay) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.platform = platform;
        this.maxRetries = maxRetries;
        this.retryBaseDelay = retryBaseDelay;
    }

    @Override
    public CompletableFuture<PushResult> send(RingingEvent event, PushTokenRecord token) {
        if (!rateLimiter.acquirePermission()) {
            metrics.incrementPushError(platform, "rate_limited");
            LOG.warn("Push rate limited platform={} callId={}", platform, event.callId());
            return CompletableFuture.completedFuture(
                    PushResult.failure(429, "rate_limited", false));
        }
        if (!breaker.tryAcquirePermission()) {
            metrics.incrementPushError(platform, "circuit_open");
            LOG.warn("Push circuit open platform={} callId={}", platform, event.callId());
            return CompletableFuture.completedFuture(
                    PushResult.failure(0, "circuit_open", false));
        }
        return sendWithRetry(event, token, 0);
    }

    private CompletableFuture<PushResult> sendWithRetry(
            RingingEvent event,
            PushTokenRecord token,
            int attempt) {
        long start = System.nanoTime();
        return delegate.send(event, token).thenCompose(result -> {
            recordBreaker(start, result);
            if (shouldRetry(result) && attempt < maxRetries) {
                long delayMs = retryDelayMs(attempt);
                LOG.info(
                        "Retrying push platform={} callId={} status={} attempt={} delayMs={}",
                        platform,
                        event.callId(),
                        result.statusCode(),
                        attempt + 1,
                        delayMs);
                return delayed(delayMs).thenCompose(v -> {
                    // Retries still consume rate-limiter / breaker permission for fairness.
                    if (!rateLimiter.acquirePermission()) {
                        return CompletableFuture.completedFuture(
                                PushResult.failure(429, "rate_limited", false));
                    }
                    if (!breaker.tryAcquirePermission()) {
                        return CompletableFuture.completedFuture(
                                PushResult.failure(0, "circuit_open", false));
                    }
                    return sendWithRetry(event, token, attempt + 1);
                });
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private void recordBreaker(long startNanos, PushResult result) {
        long duration = System.nanoTime() - startNanos;
        if (result.success()) {
            breaker.onSuccess(duration, TimeUnit.NANOSECONDS);
            return;
        }
        // Auth failures and invalid tokens are not infra outages for the breaker.
        if (result.statusCode() == 401
                || result.statusCode() == 403
                || result.tokenInvalid()) {
            breaker.onSuccess(duration, TimeUnit.NANOSECONDS);
            return;
        }
        breaker.onError(
                duration,
                TimeUnit.NANOSECONDS,
                new RuntimeException(result.errorCode() == null ? "push_error" : result.errorCode()));
    }

    static boolean shouldRetry(PushResult result) {
        if (result.success() || result.tokenInvalid()) {
            return false;
        }
        int code = result.statusCode();
        return code == 429 || code == 503;
    }

    private long retryDelayMs(int attempt) {
        long base = Math.max(1L, retryBaseDelay.toMillis());
        long exp = base * (1L << Math.min(attempt, 4));
        long jitter = ThreadLocalRandom.current().nextLong(0, base + 1);
        return exp + jitter;
    }

    private static CompletableFuture<Void> delayed(long delayMs) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(() -> future.complete(null));
        return future;
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public PushClient delegate() {
        return delegate;
    }
}
