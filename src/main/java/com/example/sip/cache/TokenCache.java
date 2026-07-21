package com.example.sip.cache;

import com.example.sip.config.GatewayConfig;
import com.example.sip.model.PushTokenRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

/**
 * In-memory LRU token cache keyed by normalized callee MSISDN.
 */
public final class TokenCache {

    private final int maxEntries;
    private final Duration ttl;
    private final Duration idleEvict;
    private final Clock clock;
    private final LongConsumer sizeReporter;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, CacheEntry> entries;

    public TokenCache(GatewayConfig config) {
        this(config, Clock.systemUTC(), size -> {
        });
    }

    public TokenCache(GatewayConfig config, Clock clock, LongConsumer sizeReporter) {
        this.maxEntries = config.cacheMaxEntries();
        this.ttl = config.cacheTtl();
        this.idleEvict = config.cacheIdleEvict();
        this.clock = clock;
        this.sizeReporter = sizeReporter;
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    public Optional<PushTokenRecord> get(String normalizedCallee) {
        lock.lock();
        try {
            CacheEntry entry = entries.get(normalizedCallee);
            if (entry == null) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            if (entry.isExpired(now, ttl, idleEvict)) {
                entries.remove(normalizedCallee);
                reportSize();
                return Optional.empty();
            }
            entry.touch(now);
            return Optional.of(entry.record());
        } finally {
            lock.unlock();
        }
    }

    public void put(String normalizedCallee, PushTokenRecord record) {
        lock.lock();
        try {
            Instant now = clock.instant();
            entries.put(normalizedCallee, new CacheEntry(record, now));
            while (entries.size() > maxEntries) {
                Iterator<Map.Entry<String, CacheEntry>> it = entries.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                } else {
                    break;
                }
            }
            reportSize();
        } finally {
            lock.unlock();
        }
    }

    public void invalidate(String normalizedCallee) {
        lock.lock();
        try {
            entries.remove(normalizedCallee);
            reportSize();
        } finally {
            lock.unlock();
        }
    }

    public int evictExpired() {
        lock.lock();
        try {
            Instant now = clock.instant();
            int removed = 0;
            Iterator<Map.Entry<String, CacheEntry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, CacheEntry> entry = it.next();
                if (entry.getValue().isExpired(now, ttl, idleEvict)) {
                    it.remove();
                    removed++;
                }
            }
            reportSize();
            return removed;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    private void reportSize() {
        sizeReporter.accept(entries.size());
    }

    private static final class CacheEntry {
        private final PushTokenRecord record;
        private final Instant cachedAt;
        private Instant lastAccess;

        private CacheEntry(PushTokenRecord record, Instant now) {
            this.record = record;
            this.cachedAt = now;
            this.lastAccess = now;
        }

        private PushTokenRecord record() {
            return record;
        }

        private void touch(Instant now) {
            this.lastAccess = now;
        }

        private boolean isExpired(Instant now, Duration ttl, Duration idleEvict) {
            return cachedAt.plus(ttl).isBefore(now) || lastAccess.plus(idleEvict).isBefore(now);
        }
    }
}
