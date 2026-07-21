package com.example.sip.harness;

import com.example.sip.diameter.ShClientApi;
import com.example.sip.model.PushTokenRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory Sh client for component tests. Script tokens, delays, and empty answers.
 */
public final class FakeShClient implements ShClientApi {

    public record UdrCall(String calleeMsisdn, String destinationRealm) {
    }

    public record PurCall(String calleeMsisdn, String destinationRealm, long sequenceNumber) {
    }

    private final Map<String, PushTokenRecord> tokensByCallee = new ConcurrentHashMap<>();
    private final List<UdrCall> udrCalls = new CopyOnWriteArrayList<>();
    private final List<PurCall> purCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger udrCount = new AtomicInteger();
    private volatile Duration udrDelay = Duration.ZERO;
    private volatile boolean emptyForAll;

    public FakeShClient withToken(String normalizedCallee, PushTokenRecord record) {
        tokensByCallee.put(normalizedCallee, record);
        return this;
    }

    public FakeShClient withUdrDelay(Duration delay) {
        this.udrDelay = delay == null ? Duration.ZERO : delay;
        return this;
    }

    public FakeShClient returnEmptyForAll() {
        this.emptyForAll = true;
        return this;
    }

    public List<UdrCall> udrCalls() {
        return List.copyOf(udrCalls);
    }

    public List<PurCall> purCalls() {
        return List.copyOf(purCalls);
    }

    public int udrCount() {
        return udrCount.get();
    }

    @Override
    public void start() {
        // no-op
    }

    @Override
    public CompletableFuture<Optional<PushTokenRecord>> userDataRequest(
            String normalizedCalleeMsisdn,
            String destinationRealm) {
        udrCount.incrementAndGet();
        udrCalls.add(new UdrCall(normalizedCalleeMsisdn, destinationRealm));
        if (udrDelay.isZero() || udrDelay.isNegative()) {
            return CompletableFuture.completedFuture(lookup(normalizedCalleeMsisdn));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(udrDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return lookup(normalizedCalleeMsisdn);
        });
    }

    private Optional<PushTokenRecord> lookup(String callee) {
        if (emptyForAll) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokensByCallee.get(callee));
    }

    @Override
    public CompletableFuture<Void> purgeToken(
            String normalizedCalleeMsisdn,
            String destinationRealm,
            long nextSequenceNumber) {
        purCalls.add(new PurCall(normalizedCalleeMsisdn, destinationRealm, nextSequenceNumber));
        tokensByCallee.remove(normalizedCalleeMsisdn);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        tokensByCallee.clear();
        udrCalls.clear();
        purCalls.clear();
    }

    public void clearHistory() {
        udrCalls.clear();
        purCalls.clear();
        udrCount.set(0);
        new ArrayList<>(tokensByCallee.keySet()); // keep tokens
    }
}
