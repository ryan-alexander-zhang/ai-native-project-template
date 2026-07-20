package com.aipersimmon.ddd.observability.otel;

import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenTelemetry implementation of {@link StoreAndForwardTracer}.
 *
 * <p>{@link #captureCurrent()} injects the active context into a carrier via the W3C
 * propagator and returns the resulting {@code traceparent}/{@code tracestate}. {@link
 * #restore(String, String, String)} extracts the stored context and starts a span that
 * <em>links</em> back to it — a link, not a parent, because a durable relay dispatches
 * long after (and fanned out from) the creating operation, so a parent-child edge would
 * distort latency and hierarchy.
 */
public final class OpenTelemetryStoreAndForwardTracer implements StoreAndForwardTracer {

    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final io.opentelemetry.api.trace.Tracer tracer;
    private final TextMapPropagator propagator;

    /**
     * @param tracer the OTEL tracer that opens the restored (linked) span
     * @param propagator the text-map propagator (W3C by default) used to inject/extract
     */
    public OpenTelemetryStoreAndForwardTracer(
            io.opentelemetry.api.trace.Tracer tracer,
            TextMapPropagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Captured captureCurrent() {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.current(), carrier, Map::put);
        String traceparent = carrier.get(TRACEPARENT);
        if (traceparent == null) {
            return Captured.NONE;
        }
        return new Captured(traceparent, carrier.get(TRACESTATE));
    }

    @Override
    public Scope restore(String traceparent, String traceState, String spanName) {
        if (traceparent == null) {
            // Nothing to link to — open a plain span so the dispatch is still visible.
            return open(tracer.spanBuilder(spanName).startSpan());
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACEPARENT, traceparent);
        if (traceState != null) {
            carrier.put(TRACESTATE, traceState);
        }
        Context creating = propagator.extract(Context.current(), carrier, GETTER);
        SpanContext creatingSpan = Span.fromContext(creating).getSpanContext();

        var builder = tracer.spanBuilder(spanName);
        if (creatingSpan.isValid()) {
            builder.addLink(creatingSpan);
        }
        return open(builder.startSpan());
    }

    private Scope open(Span span) {
        io.opentelemetry.context.Scope otelScope = span.makeCurrent();
        return new Scope() {
            @Override
            public void recordFailure(Throwable error) {
                span.setStatus(StatusCode.ERROR, error == null ? "" : String.valueOf(error.getMessage()));
                if (error != null) {
                    span.recordException(error);
                }
            }

            @Override
            public void close() {
                otelScope.close();
                span.end();
            }
        };
    }
}
