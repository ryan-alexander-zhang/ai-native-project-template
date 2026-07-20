package com.aipersimmon.ddd.observability.otel.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.observability.ObservabilityAttributes;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.observability.otel.OpenTelemetryTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the command span the interceptor adds — the auto-instrumentation cannot see
 * the in-house command bus, so this span is what makes "which command ran" visible.
 */
class TracingCommandInterceptorTest {

    private InMemorySpanExporter exporter;
    private TracingCommandInterceptor interceptor;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        Tracer tracer = new OpenTelemetryTracer(sdk.getTracer("test"));
        interceptor = new TracingCommandInterceptor(tracer);
    }

    @Test
    void wrapsSuccessfulCommandInASpanWithAttributes() {
        CommandContext context = CommandContext.root("msg-1", null);

        String result = interceptor.intercept(new PlaceOrder(), context, () -> "done");

        assertEquals("done", result);
        SpanData span = single();
        assertEquals("command PlaceOrder", span.getName());
        assertEquals("PlaceOrder",
                span.getAttributes().get(AttributeKey.stringKey(ObservabilityAttributes.COMMAND_TYPE)));
        assertEquals("msg-1",
                span.getAttributes().get(AttributeKey.stringKey(ObservabilityAttributes.MESSAGE_ID)));
        assertEquals("msg-1",
                span.getAttributes().get(AttributeKey.stringKey(ObservabilityAttributes.CORRELATION_ID)));
    }

    @Test
    void marksSpanFailedWhenHandlerThrows() {
        CommandContext context = CommandContext.root("msg-2", null);
        RuntimeException boom = new IllegalStateException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> interceptor.intercept(new PlaceOrder(), context, () -> {
                    throw boom;
                }));

        assertEquals(boom, thrown);
        SpanData span = single();
        assertEquals(StatusData.error().getStatusCode(), span.getStatus().getStatusCode());
        assertTrue(span.getEvents().stream().anyMatch(e -> "exception".equals(e.getName())),
                "the exception must be recorded on the span");
    }

    private SpanData single() {
        assertEquals(1, exporter.getFinishedSpanItems().size());
        return exporter.getFinishedSpanItems().get(0);
    }

    /** A minimal command whose simple name drives the span name. */
    record PlaceOrder() implements Command<String> {
    }
}
