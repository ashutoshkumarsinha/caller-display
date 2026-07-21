package com.example.sip.observability;

import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.resilience.GatewayResilience;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers read-only JMX MBeans under {@code com.example.pushgateway:*} (spec §8.2).
 */
public final class GatewayJmxRegistrar implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayJmxRegistrar.class);

    private final MBeanServer server;
    private final List<ObjectName> registered = new ArrayList<>();

    public GatewayJmxRegistrar() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    public GatewayJmxRegistrar(MBeanServer server) {
        this.server = server;
    }

    public void register(GatewayMetrics metrics, GatewayResilience resilience) {
        try {
            register(new ObjectName("com.example.pushgateway:type=PushStats"),
                    new PushStats(metrics));
            register(new ObjectName("com.example.pushgateway:type=HssStats"),
                    new HssStats(metrics));
            register(new ObjectName("com.example.pushgateway:type=WorkerPool"),
                    new WorkerPoolStats(metrics));
            register(new ObjectName("com.example.pushgateway:type=TokenCache"),
                    new TokenCacheStats(metrics));
            if (resilience != null) {
                registerBreaker("hss", resilience.hssBreaker());
                registerBreaker("apns", resilience.apnsBreaker());
                registerBreaker("fcm", resilience.fcmBreaker());
            }
            metrics.bindJmx(this);
            LOG.info("Registered {} gateway JMX MBeans", registered.size());
        } catch (JMException e) {
            throw new IllegalStateException("Failed to register gateway JMX beans", e);
        }
    }

    private void registerBreaker(String name, CircuitBreaker breaker) throws JMException {
        register(
                new ObjectName("com.example.pushgateway:type=CircuitBreaker,name=" + name),
                new CircuitBreakerStats(breaker));
    }

    private void register(ObjectName name, Object bean) throws JMException {
        if (server.isRegistered(name)) {
            server.unregisterMBean(name);
        }
        server.registerMBean(bean, name);
        registered.add(name);
    }

    @Override
    public void close() {
        for (ObjectName name : registered) {
            try {
                if (server.isRegistered(name)) {
                    server.unregisterMBean(name);
                }
            } catch (JMException e) {
                LOG.debug("JMX unregister {}: {}", name, e.toString());
            }
        }
        registered.clear();
    }

    public interface PushStatsMXBean {
        long getSuccessfulPushes();

        long getFailedPushes();

        long getTokenPurges();
    }

    public interface HssStatsMXBean {
        long getLookupCount();

        long getLookupFailures();

        long getCacheHits();

        long getCacheMisses();

        double getMeanLatencyMs();
    }

    public interface WorkerPoolStatsMXBean {
        long getQueueDepth();

        long getDroppedTasks();
    }

    public interface TokenCacheStatsMXBean {
        long getSize();
    }

    public interface CircuitBreakerStatsMXBean {
        String getState();

        float getFailureRate();
    }

    public static final class PushStats implements PushStatsMXBean {
        private final GatewayMetrics metrics;

        public PushStats(GatewayMetrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public long getSuccessfulPushes() {
            return metrics.totalPushSuccess();
        }

        @Override
        public long getFailedPushes() {
            return metrics.totalPushErrors();
        }

        @Override
        public long getTokenPurges() {
            return metrics.pushTokenPurges();
        }
    }

    public static final class HssStats implements HssStatsMXBean {
        private final GatewayMetrics metrics;

        public HssStats(GatewayMetrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public long getLookupCount() {
            return metrics.hssLookupCount();
        }

        @Override
        public long getLookupFailures() {
            return metrics.hssCacheMisses(); // approximate; detailed causes in Prometheus tags
        }

        @Override
        public long getCacheHits() {
            return metrics.hssCacheHits();
        }

        @Override
        public long getCacheMisses() {
            return metrics.hssCacheMisses();
        }

        @Override
        public double getMeanLatencyMs() {
            return metrics.hssLookupMeanMillis();
        }
    }

    public static final class WorkerPoolStats implements WorkerPoolStatsMXBean {
        private final GatewayMetrics metrics;

        public WorkerPoolStats(GatewayMetrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public long getQueueDepth() {
            return metrics.workerQueueDepth();
        }

        @Override
        public long getDroppedTasks() {
            return metrics.workerDrops();
        }
    }

    public static final class TokenCacheStats implements TokenCacheStatsMXBean {
        private final GatewayMetrics metrics;

        public TokenCacheStats(GatewayMetrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public long getSize() {
            return metrics.tokenCacheSize();
        }
    }

    public static final class CircuitBreakerStats implements CircuitBreakerStatsMXBean {
        private final CircuitBreaker breaker;

        public CircuitBreakerStats(CircuitBreaker breaker) {
            this.breaker = breaker;
        }

        @Override
        public String getState() {
            return breaker.getState().name();
        }

        @Override
        public float getFailureRate() {
            return breaker.getMetrics().getFailureRate();
        }
    }
}
