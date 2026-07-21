package com.example.sip.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically invokes {@link OauthBearerTokenProvider#ensureFresh()} so tokens
 * rotate before expiry without waiting for a 401/403.
 */
public final class TokenRefreshScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TokenRefreshScheduler.class);

    private final ScheduledExecutorService executor;
    private final Duration pollInterval;
    private final boolean ownsExecutor;

    public TokenRefreshScheduler(Clock clock, Duration pollInterval) {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oauth-token-refresh");
            t.setDaemon(true);
            return t;
        }), pollInterval, true);
        Objects.requireNonNull(clock, "clock");
    }

    public TokenRefreshScheduler(ScheduledExecutorService executor, Duration pollInterval) {
        this(executor, pollInterval, false);
    }

    private TokenRefreshScheduler(
            ScheduledExecutorService executor,
            Duration pollInterval,
            boolean ownsExecutor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.ownsExecutor = ownsExecutor;
    }

    public void watch(OauthBearerTokenProvider provider) {
        Objects.requireNonNull(provider, "provider");
        long periodMs = Math.max(10L, pollInterval.toMillis());
        executor.scheduleAtFixedRate(() -> {
            try {
                if (provider.ensureFresh()) {
                    LOG.info("Proactive OAuth/vault token refresh succeeded");
                }
            } catch (RuntimeException ex) {
                LOG.warn("Proactive token refresh tick failed: {}", ex.toString());
            }
        }, 0L, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (!ownsExecutor) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
