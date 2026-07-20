package com.aipersimmon.ddd.observability.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.observability.ObservabilityAttributes;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Captured;
import com.aipersimmon.ddd.observability.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the OTEL SPI implementations against a real SDK with an in-memory exporter, so
 * the assertions are on actually-emitted spans, links and context — not mocks.
 */
class OpenTelemetryObservabilityTest {

    private InMemorySpanExporter exporter;
    private io.opentelemetry.api.trace.Tracer otelTracer;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        otelTracer = sdk.getTracer("test");
    }

    @Test
    void domainSpanCarriesNameAndAttributes() {
        Tracer tracer = new OpenTelemetryTracer(otelTracer);

        try (Tracer.SpanScope span = tracer.startSpan("command PlaceOrder")) {
            span.attribute(ObservabilityAttributes.COMMAND_TYPE, "PlaceOrder")
                    .attribute(ObservabilityAttributes.RETRY_ATTEMPT, 2L);
        }

        SpanData span = single();
        assertEquals("command PlaceOrder", span.getName());
        assertEquals("PlaceOrder", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                ObservabilityAttributes.COMMAND_TYPE)));
        assertEquals(2L, span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey(
                ObservabilityAttributes.RETRY_ATTEMPT)));
    }

    @Test
    void captureSerialisesActiveContextIncludingSampledFlag() {
        Span creating = otelTracer.spanBuilder("command PlaceOrder").startSpan();
        StoreAndForwardTracer sf = new OpenTelemetryStoreAndForwardTracer(
                otelTracer, sdk.getPropagators().getTextMapPropagator());

        Captured captured;
        try (Scope ignored = creating.makeCurrent()) {
            captured = sf.captureCurrent();
        }
        creating.end();

        assertNotNull(captured.traceparent());
        assertTrue(captured.traceparent().contains(creating.getSpanContext().getTraceId()),
                "traceparent must carry the creating span's trace id");
        // W3C trace-flags is the last hex pair; the sampled bit (0x01) must survive so the
        // restored span is exported (recent OTEL also sets the random-trace-id bit 0x02).
        String flags = captured.traceparent().substring(captured.traceparent().lastIndexOf('-') + 1);
        assertEquals(1, Integer.parseInt(flags, 16) & 0x01,
                "sampled flag must survive; traceparent was " + captured.traceparent());
    }

    @Test
    void restoreLinksBackToCreatingSpanAsNewTrace() {
        Span creating = otelTracer.spanBuilder("command PlaceOrder").startSpan();
        StoreAndForwardTracer sf = new OpenTelemetryStoreAndForwardTracer(
                otelTracer, sdk.getPropagators().getTextMapPropagator());
        Captured captured;
        try (Scope ignored = creating.makeCurrent()) {
            captured = sf.captureCurrent();
        }
        creating.end();

        try (StoreAndForwardTracer.Scope ignored =
                sf.restore(captured.traceparent(), captured.traceState(), "effect.dispatch effect-1")) {
            // dispatch happens here
        }

        SpanData restored = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().startsWith("effect.dispatch"))
                .findFirst()
                .orElseThrow();
        assertEquals("effect.dispatch effect-1", restored.getName());

        List<LinkData> links = restored.getLinks();
        assertEquals(1, links.size(), "restored span must link to the creating span");
        assertEquals(creating.getSpanContext().getTraceId(), links.get(0).getSpanContext().getTraceId(),
                "link must point at the creating trace");
        assertNotEquals(creating.getSpanContext().getTraceId(), restored.getSpanContext().getTraceId(),
                "restored dispatch is a new trace linked to (not a child of) the creating span");
    }

    @Test
    void captureWithoutActiveSpanYieldsNone() {
        StoreAndForwardTracer sf = new OpenTelemetryStoreAndForwardTracer(
                otelTracer, sdk.getPropagators().getTextMapPropagator());

        Captured captured = sf.captureCurrent();

        assertNull(captured.traceparent());
        assertEquals(Captured.NONE, captured);
    }

    private SpanData single() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        return spans.get(0);
    }
}
