package com.example.sip.security;

import com.example.sip.push.PushAuthTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bearer provider that refreshes OAuth/Vault tokens before expiry (spec §10.2)
 * and on explicit {@link #refresh()} (401/403 path).
 */
public final class OauthBearerTokenProvider implements PushAuthTokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OauthBearerTokenProvider.class);

    private final AtomicReference<AccessToken> token;
    private final Duration refreshSkew;
    private final TokenRefreshClient refreshClient;
    private final Clock clock;
    private final AtomicInteger refreshCount = new AtomicInteger();

    public OauthBearerTokenProvider(
            AccessToken initial,
            Duration refreshSkew,
            TokenRefreshClient refreshClient,
            Clock clock) {
        this.token = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.refreshSkew = Objects.requireNonNull(refreshSkew, "refreshSkew");
        this.refreshClient = Objects.requireNonNull(refreshClient, "refreshClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String currentToken() {
        ensureFresh();
        return token.get().value();
    }

    /**
     * Proactively refresh when within {@code refreshSkew} of expiry.
     *
     * @return true if a refresh was attempted and succeeded
     */
    public boolean ensureFresh() {
        AccessToken current = token.get();
        Instant deadline = current.expiresAt().minus(refreshSkew);
        if (clock.instant().isBefore(deadline)) {
            return false;
        }
        return refresh();
    }

    @Override
    public boolean refresh() {
        refreshCount.incrementAndGet();
        try {
            AccessToken next = refreshClient.refresh();
            if (next == null || next.isBlank()) {
                LOG.warn("OAuth/vault token refresh returned empty credential");
                return false;
            }
            token.set(next);
            return true;
        } catch (RuntimeException ex) {
            LOG.warn("OAuth/vault token refresh failed: {}", ex.toString());
            return false;
        }
    }

    @Override
    public int refreshCount() {
        return refreshCount.get();
    }

    public Instant expiresAt() {
        return token.get().expiresAt();
    }

    public Duration refreshSkew() {
        return refreshSkew;
    }
}
