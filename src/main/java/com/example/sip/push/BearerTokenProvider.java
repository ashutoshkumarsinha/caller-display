package com.example.sip.push;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Mutable bearer holder with optional refresh supplier (Vault/OAuth hook in later phases).
 */
public final class BearerTokenProvider implements PushAuthTokenProvider {

    private final AtomicReference<String> token;
    private final AtomicInteger refreshCount = new AtomicInteger();
    private final Supplier<String> refresher;

    public BearerTokenProvider(String initialToken) {
        this(initialToken, null);
    }

    public BearerTokenProvider(String initialToken, Supplier<String> refresher) {
        this.token = new AtomicReference<>(initialToken == null ? "" : initialToken);
        this.refresher = refresher;
    }

    @Override
    public String currentToken() {
        String value = token.get();
        return value == null ? "" : value;
    }

    public void setToken(String value) {
        token.set(value == null ? "" : value);
    }

    @Override
    public boolean refresh() {
        refreshCount.incrementAndGet();
        if (refresher == null) {
            return false;
        }
        String next = refresher.get();
        if (next == null || next.isBlank()) {
            return false;
        }
        token.set(next);
        return true;
    }

    @Override
    public int refreshCount() {
        return refreshCount.get();
    }

    public static BearerTokenProvider of(String token) {
        return new BearerTokenProvider(Objects.requireNonNullElse(token, ""));
    }
}
