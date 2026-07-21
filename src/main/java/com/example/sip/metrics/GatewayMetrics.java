package com.example.sip.metrics;

import com.example.sip.observability.GatewayJmxRegistrar;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local counters/gauges mirrored to Micrometer (Prometheus/MP Metrics scrape path).
 */
public final class GatewayMetrics {

    private final MeterRegistry registry;
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
    private final AtomicLong hssLookupSamples = new AtomicLong();
    private final AtomicLong hssLookupTotalNanos = new AtomicLong();
    private final AtomicLong sipHandlerSamples = new AtomicLong();
    private final AtomicLong sipHandlerTotalNanos = new AtomicLong();
    private final AtomicLong sipHandlerMaxNanos = new AtomicLong();
    private volatile GatewayJmxRegistrar jmx;

    public GatewayMetrics() {
        this(new SimpleMeterRegistry());
    }

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("token_cache_size", tokenCacheSize, AtomicLong::get).register(registry);
        Gauge.builder("worker_pool_queue_depth", workerQueueDepth, AtomicLong::get).register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    public void bindJmx(GatewayJmxRegistrar jmx) {
        this.jmx = jmx;
    }

    public void incrementSipRinging() {
        sipRingingIntercepts.incrementAndGet();
        Counter.builder("sip_ringing_intercepts_total").register(registry).increment();
    }

    public void incrementCacheHit() {
        hssCacheHits.incrementAndGet();
        Counter.builder("hss_cache_hit_total").register(registry).increment();
    }

    public void incrementCacheMiss() {
        hssCacheMisses.incrementAndGet();
        Counter.builder("hss_cache_miss_total").register(registry).increment();
    }

    public void incrementHssFailure(String realm, String cause) {
        hssFailuresByRealm
                .computeIfAbsent(realm + "|" + cause, k -> new AtomicLong())
                .incrementAndGet();
        Counter.builder("hss_lookup_failures_total")
                .tag("destination_realm", safe(realm))
                .tag("cause", safe(cause))
                .register(registry)
                .increment();
    }

    public void incrementRealmRoute(String realm) {
        realmRoutes.computeIfAbsent(realm, k -> new AtomicLong()).incrementAndGet();
        Counter.builder("hss_realm_route_total")
                .tag("destination_realm", safe(realm))
                .register(registry)
                .increment();
    }

    public void incrementRealmNoPeer(String realm) {
        realmNoPeer.computeIfAbsent(realm, k -> new AtomicLong()).incrementAndGet();
        Counter.builder("hss_realm_no_peer_total")
                .tag("destination_realm", safe(realm))
                .register(registry)
                .increment();
    }

    public void incrementPushSuccess(String platform) {
        pushSuccessByPlatform.computeIfAbsent(platform, k -> new AtomicLong()).incrementAndGet();
        Counter.builder("push_delivery_success_total")
                .tag("platform", safe(platform))
                .register(registry)
                .increment();
    }

    public void incrementPushError(String platform, String code) {
        pushErrorsByPlatform
                .computeIfAbsent(platform + "|" + code, k -> new AtomicLong())
                .incrementAndGet();
        Counter.builder("push_delivery_errors_total")
                .tag("platform", safe(platform))
                .tag("code", safe(code))
                .register(registry)
                .increment();
    }

    public void incrementTokenPurge() {
        pushTokenPurges.incrementAndGet();
        Counter.builder("push_token_purge_total").register(registry).increment();
    }

    public void incrementWorkerDrop() {
        workerDrops.incrementAndGet();
        Counter.builder("worker_pool_dropped_total").register(registry).increment();
    }

    public void setTokenCacheSize(long size) {
        tokenCacheSize.set(size);
    }

    public void setWorkerQueueDepth(long depth) {
        workerQueueDepth.set(depth);
    }

    public void recordHssLookup(Duration duration, String realm) {
        long nanos = duration.toNanos();
        hssLookupSamples.incrementAndGet();
        hssLookupTotalNanos.addAndGet(nanos);
        Timer.builder("hss_lookup_latency_seconds")
                .tag("destination_realm", safe(realm))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** SIP-thread extract + enqueue latency (spec §12 budget: &lt; 5 ms). */
    public void recordSipHandler(Duration duration) {
        long nanos = duration.toNanos();
        sipHandlerSamples.incrementAndGet();
        sipHandlerTotalNanos.addAndGet(nanos);
        sipHandlerMaxNanos.accumulateAndGet(nanos, Math::max);
        Timer.builder("sip_handler_latency_seconds")
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public long hssLookupCount() {
        return hssLookupSamples.get();
    }

    public double hssLookupMeanMillis() {
        long samples = hssLookupSamples.get();
        if (samples == 0) {
            return 0d;
        }
        return (hssLookupTotalNanos.get() / (double) samples) / 1_000_000d;
    }

    public long sipHandlerCount() {
        return sipHandlerSamples.get();
    }

    public double sipHandlerMeanMillis() {
        long samples = sipHandlerSamples.get();
        if (samples == 0) {
            return 0d;
        }
        return (sipHandlerTotalNanos.get() / (double) samples) / 1_000_000d;
    }

    public double sipHandlerMaxMillis() {
        return sipHandlerMaxNanos.get() / 1_000_000d;
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

    public long hssFailureCount(String realm, String cause) {
        AtomicLong value = hssFailuresByRealm.get(realm + "|" + cause);
        return value == null ? 0L : value.get();
    }

    public long realmNoPeerCount(String realm) {
        AtomicLong value = realmNoPeer.get(realm);
        return value == null ? 0L : value.get();
    }

    public long realmRouteCount(String realm) {
        AtomicLong value = realmRoutes.get(realm);
        return value == null ? 0L : value.get();
    }

    public long pushSuccessCount(String platform) {
        AtomicLong value = pushSuccessByPlatform.get(platform);
        return value == null ? 0L : value.get();
    }

    public long pushErrorCount(String platform, String code) {
        AtomicLong value = pushErrorsByPlatform.get(platform + "|" + code);
        return value == null ? 0L : value.get();
    }

    public long totalPushSuccess() {
        return pushSuccessByPlatform.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public long totalPushErrors() {
        return pushErrorsByPlatform.values().stream().mapToLong(AtomicLong::get).sum();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
