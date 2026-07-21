package com.example.sip.observability;

import com.example.sip.model.RingingEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * OpenTelemetry spans for ringing → HSS → push (spec §8 / Phase 4.2).
 *
 * <p>Uses {@link GlobalOpenTelemetry}; under Liberty mpTelemetry the global provider is wired by the runtime.
 * Unit tests install an in-memory SDK via {@link #installTracer(Tracer)}.
 */
public final class GatewayTracing {

    private static final String INSTRUMENTATION = "sip-http-push-gateway";

    private volatile Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION);

    public void installTracer(Tracer tracer) {
        this.tracer = tracer == null ? GlobalOpenTelemetry.getTracer(INSTRUMENTATION) : tracer;
    }

    public SpanScope startRingingSpan(RingingEvent event) {
        Span span = tracer.spanBuilder("gateway.ringing.process")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("call.id", event.callId())
                .setAttribute("event.id", event.eventId())
                .setAttribute("callee.msisdn", event.calleeMsisdn())
                .setAttribute("destination.realm", event.destinationRealm())
                .startSpan();
        Scope scope = span.makeCurrent();
        return new SpanScope(span, scope);
    }

    public SpanScope startChild(String name) {
        Span span = tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan();
        Scope scope = span.makeCurrent();
        return new SpanScope(span, scope);
    }

    public static final class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private SpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public Span span() {
            return span;
        }

        public void recordException(Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
