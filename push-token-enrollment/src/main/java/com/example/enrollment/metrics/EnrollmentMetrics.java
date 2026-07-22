package com.example.enrollment.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Enrollment counters and SPML latency timer.
 */
public final class EnrollmentMetrics {

    private final MeterRegistry registry;

    public EnrollmentMetrics() {
        this(new SimpleMeterRegistry());
    }

    public EnrollmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordUpsert(String platform, String result) {
        Counter.builder("enrollment_upsert_total")
                .tag("platform", safe(platform))
                .tag("result", safe(result))
                .register(registry)
                .increment();
    }

    public void recordClear(String result) {
        Counter.builder("enrollment_clear_total")
                .tag("result", safe(result))
                .register(registry)
                .increment();
    }

    public void recordSpmlLatency(Duration duration) {
        Timer.builder("enrollment_spml_latency_seconds")
                .register(registry)
                .record(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    public MeterRegistry registry() {
        return registry;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
