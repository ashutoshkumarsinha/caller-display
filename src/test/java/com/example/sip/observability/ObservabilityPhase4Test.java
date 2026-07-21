package com.example.sip.observability;

import com.example.sip.cache.TokenCache;
import com.example.sip.config.GatewayConfig;
import com.example.sip.harness.FakeShClient;
import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.PushClient;
import com.example.sip.resilience.GatewayResilience;
import com.example.sip.worker.AsyncWorkerPool;
import com.example.sip.worker.RingingProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import javax.management.ObjectName;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1–T4.3 — Micrometer counters/timers, MDC correlation, OTel spans.
 */
class ObservabilityPhase4Test {

    private MeterRegistry registry;
    private GatewayMetrics metrics;
    private GatewayConfig config;
    private AsyncWorkerPool cleanupPool;
    private FakeShClient shClient;
    private TokenCache cache;
    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;
    private GatewayTracing tracing;
    private GatewayJmxRegistrar jmx;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new GatewayMetrics(registry);
        config = GatewayConfig.builder()
                .cacheMaxEntries(100)
                .cacheTtl(Duration.ofSeconds(300))
                .cacheIdleEvict(Duration.ofSeconds(120))
                .workerCorePoolSize(2)
                .workerMaxPoolSize(4)
                .workerQueueCapacity(16)
                .build();
        cleanupPool = AsyncWorkerPool.sameThread(config, metrics);
        cache = new TokenCache(config);
        shClient = new FakeShClient().withToken(
                RingingEventFixtures.CALLEE_E164,
                new PushTokenRecord("tok", PushPlatform.APNS, 1));

        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        tracing = new GatewayTracing();
        tracing.installTracer(tracer);

        jmx = new GatewayJmxRegistrar();
        jmx.register(metrics, GatewayResilience.forTests());
    }

    @AfterEach
    void tearDown() {
        cleanupPool.close();
        shClient.close();
        tracerProvider.close();
        jmx.close();
        MDC.clear();
    }

    @Test
    void successfulPathIncrementsSipAndPushMicrometerCounters() {
        metrics.incrementSipRinging();
        PushClient apns = (event, token) -> CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        PushClient fcm = (event, token) -> CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        RingingProcessor processor =
                new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool, tracing);

        processor.process(RingingEventFixtures.ringingE164());

        assertEquals(1L, metrics.sipRingingIntercepts());
        assertEquals(1L, metrics.pushSuccessCount("APNS"));
        assertEquals(1.0, registry.get("sip_ringing_intercepts_total").counter().count());
        assertEquals(1.0, registry.get("push_delivery_success_total").tag("platform", "APNS").counter().count());
        assertTrue(registry.get("hss_lookup_latency_seconds").timer().count() >= 1);
        assertEquals(1L, metrics.hssLookupCount());
    }

    @Test
    void processorPutsCallIdInMdcDuringPush() {
        AtomicReference<String> mdcCallId = new AtomicReference<>();
        AtomicReference<String> mdcEventId = new AtomicReference<>();
        AtomicReference<String> mdcPlatform = new AtomicReference<>();

        PushClient apns = (event, token) -> {
            mdcCallId.set(CallMdc.get(CallMdc.CALL_ID));
            mdcEventId.set(CallMdc.get(CallMdc.EVENT_ID));
            mdcPlatform.set(CallMdc.get(CallMdc.PLATFORM));
            return CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        };
        PushClient fcm = (event, token) -> CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        RingingProcessor processor =
                new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool, tracing);

        RingingEvent event = RingingEventFixtures.ringingE164();
        processor.process(event);

        assertEquals(event.callId(), mdcCallId.get());
        assertEquals(event.eventId(), mdcEventId.get());
        assertEquals("APNS", mdcPlatform.get());
        assertTrue(CallMdc.get(CallMdc.CALL_ID) == null);
    }

    @Test
    void tracingEmitsRingingHssAndPushSpans() {
        PushClient apns = (event, token) -> CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        PushClient fcm = (event, token) -> CompletableFuture.completedFuture(PushClient.PushResult.ok(200));
        RingingProcessor processor =
                new RingingProcessor(cache, shClient, apns, fcm, metrics, cleanupPool, tracing);

        RingingEvent event = RingingEventFixtures.ringingE164();
        processor.process(event);
        tracerProvider.forceFlush();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("gateway.ringing.process")));
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("gateway.hss.udr")));
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("gateway.push.send")));

        SpanData root = spans.stream()
                .filter(s -> s.getName().equals("gateway.ringing.process"))
                .findFirst()
                .orElseThrow();
        assertEquals(event.callId(), root.getAttributes().get(AttributeKey.stringKey("call.id")));
    }

    @Test
    void jmxPushStatsExposeSuccessCounts() throws Exception {
        metrics.incrementPushSuccess("APNS");
        metrics.incrementPushSuccess("FCM");
        metrics.incrementTokenPurge();

        var server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.example.pushgateway:type=PushStats");
        assertTrue(server.isRegistered(name));
        assertEquals(2L, server.getAttribute(name, "SuccessfulPushes"));
        assertEquals(1L, server.getAttribute(name, "TokenPurges"));
    }
}
