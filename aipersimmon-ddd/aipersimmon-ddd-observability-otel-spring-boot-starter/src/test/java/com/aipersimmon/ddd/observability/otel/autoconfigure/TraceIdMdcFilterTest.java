package com.aipersimmon.ddd.observability.otel.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter must expose the active OTEL trace id on the MDC for the request's duration so the
 * web error body can carry a lookup-able trace id, and clear it afterwards.
 */
class TraceIdMdcFilterTest {

    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build())
            .build();

    @Test
    void putsTheActiveTraceIdOnTheMdcDuringTheRequestAndClearsItAfter() throws Exception {
        Span span = sdk.getTracer("test").spanBuilder("server").startSpan();
        String[] seenDuringRequest = new String[1];

        try (Scope ignored = span.makeCurrent()) {
            new TraceIdMdcFilter().doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(),
                    (req, res) -> seenDuringRequest[0] = MDC.get(TraceIdMdcFilter.TRACE_ID_MDC_KEY));
        }

        assertEquals(span.getSpanContext().getTraceId(), seenDuringRequest[0],
                "the real trace id must be visible on the MDC while the request is handled");
        assertNull(MDC.get(TraceIdMdcFilter.TRACE_ID_MDC_KEY), "and cleared once the request completes");
    }

    @Test
    void putsNothingWhenNoSpanIsActive() throws Exception {
        String[] seenDuringRequest = {"sentinel"};

        new TraceIdMdcFilter().doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seenDuringRequest[0] = MDC.get(TraceIdMdcFilter.TRACE_ID_MDC_KEY));

        assertNull(seenDuringRequest[0], "no active span means no trace id on the MDC");
    }
}
