package com.example.sip.worker;

import com.example.sip.config.GatewayConfig;
import com.example.sip.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async worker pool with discard-oldest backpressure for SIP handoff tasks.
 */
public final class AsyncWorkerPool implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncWorkerPool.class);

    private final ThreadPoolExecutor executor;
    private final GatewayMetrics metrics;

    public AsyncWorkerPool(GatewayConfig config, GatewayMetrics metrics) {
        this.metrics = metrics;
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(config.workerQueueCapacity());
        RejectedExecutionHandler rejection = "DISCARD_OLDEST".equalsIgnoreCase(config.workerQueueDropPolicy())
                ? new ThreadPoolExecutor.DiscardOldestPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        metrics.incrementWorkerDrop();
                        LOG.warn("Worker queue full; discarding oldest task");
                        super.rejectedExecution(r, e);
                    }
                }
                : (r, e) -> {
                    metrics.incrementWorkerDrop();
                    LOG.warn("Worker queue full; dropping task");
                };

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "push-gateway-worker-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        this.executor = new ThreadPoolExecutor(
                config.workerCorePoolSize(),
                config.workerMaxPoolSize(),
                60L,
                TimeUnit.SECONDS,
                queue,
                factory,
                rejection);
    }

    public void execute(Runnable task) {
        metrics.setWorkerQueueDepth(executor.getQueue().size());
        executor.execute(() -> {
            try {
                task.run();
            } finally {
                metrics.setWorkerQueueDepth(executor.getQueue().size());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
