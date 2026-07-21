package com.example.sip.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local counters/gauges. Wire to MP Metrics / OTel exporters at runtime.
 */
public final class GatewayMetrics {

    private final AtomicLong sipRingingIntercepts = new AtomicLong();
    private final AtomicLong hssCacheHits = new AtomicLong();
    private final AtomicLong hssCacheMisses = new AtomicLong();
    private final AtomicLong pushTokenPurges = new AtomicLong();
    private final AtomicLong workerDrops = new AtomicLong();
    private final AtomicLong tokenCacheSize = new AtomicLong();
    private final AtomicLong workerQueueDepth = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> hssFailuresByRealm = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> pushSuccessByPlatform = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> pushErrorsByPlatform = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> realmRoutes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> realmNoPeer = new ConcurrentHashMap<>();

    public void incrementSipRinging() {
        sipRingingIntercepts.incrementAndGet();
    }

    public void incrementCacheHit() {
        hssCacheHits.incrementAndGet();
    }

    public void incrementCacheMiss() {
        hssCacheMisses.incrementAndGet();
    }

    public void incrementHssFailure(String realm, String cause) {
        hssFailuresByRealm
                .computeIfAbsent(realm + "|" + cause, k -> new AtomicLong())
                .incrementAndGet();
    }

    public void incrementRealmRoute(String realm) {
        realmRoutes.computeIfAbsent(realm, k -> new AtomicLong()).incrementAndGet();
    }

    public void incrementRealmNoPeer(String realm) {
        realmNoPeer.computeIfAbsent(realm, k -> new AtomicLong()).incrementAndGet();
    }

    public void incrementPushSuccess(String platform) {
        pushSuccessByPlatform.computeIfAbsent(platform, k -> new AtomicLong()).incrementAndGet();
    }

    public void incrementPushError(String platform, String code) {
        pushErrorsByPlatform
                .computeIfAbsent(platform + "|" + code, k -> new AtomicLong())
                .incrementAndGet();
    }

    public void incrementTokenPurge() {
        pushTokenPurges.incrementAndGet();
    }

    public void incrementWorkerDrop() {
        workerDrops.incrementAndGet();
    }

    public void setTokenCacheSize(long size) {
        tokenCacheSize.set(size);
    }

    public void setWorkerQueueDepth(long depth) {
        workerQueueDepth.set(depth);
    }

    public long sipRingingIntercepts() {
        return sipRingingIntercepts.get();
    }

    public long hssCacheHits() {
        return hssCacheHits.get();
    }

    public long hssCacheMisses() {
        return hssCacheMisses.get();
    }

    public long pushTokenPurges() {
        return pushTokenPurges.get();
    }

    public long workerDrops() {
        return workerDrops.get();
    }

    public long tokenCacheSize() {
        return tokenCacheSize.get();
    }

    public long workerQueueDepth() {
        return workerQueueDepth.get();
    }
}
