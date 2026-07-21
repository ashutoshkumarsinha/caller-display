package com.example.sip.worker;

import com.example.sip.config.GatewayConfig;
import com.example.sip.metrics.GatewayMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.4 — discard-oldest queue drop increments metrics without throwing.
 */
class AsyncWorkerPoolBackpressureTest {

    @Test
    void fullQueueDiscardsOldestAndIncrementsDropMetric() throws Exception {
        GatewayConfig config = GatewayConfig.builder()
                .workerCorePoolSize(1)
                .workerMaxPoolSize(1)
                .workerQueueCapacity(1)
                .workerQueueDropPolicy("DISCARD_OLDEST")
                .build();
        GatewayMetrics metrics = new GatewayMetrics();
        AsyncWorkerPool pool = new AsyncWorkerPool(config, metrics);

        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();

        // Occupy the single worker.
        pool.execute(() -> {
            started.countDown();
            try {
                blocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));

        // Fill the queue (capacity 1), then overflow to trigger discard-oldest.
        pool.execute(ran::incrementAndGet);
        pool.execute(ran::incrementAndGet);
        pool.execute(ran::incrementAndGet);

        assertTrue(metrics.workerDrops() >= 1L);

        blocker.countDown();
        pool.close();
    }
}
