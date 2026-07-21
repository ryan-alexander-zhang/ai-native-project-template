package com.aipersimmon.ddd.observability.otel.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter must expose the active OTEL trace id on the MDC for the request's duration so the web
 * error body can carry a lookup-able trace id, and clear it afterwards.
 */
class TraceIdMdcFilterTest {

  private final OpenTelemetrySdk sdk =
      OpenTelemetrySdk.builder()
          .setTracerProvider(SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build())
          .build();

  @Test
  void putsTheActiveTraceIdOnTheMdcDuringTheRequestAndClearsItAfter() throws Exception {
    Span span = sdk.getTracer("test").spanBuilder("server").startSpan();
    String[] seenDuringRequest = new String[1];

    try (Scope ignored = span.makeCurrent()) {
      new TraceIdMdcFilter()
          .doFilter(
              new MockHttpServletRequest(),
              new MockHttpServletResponse(),
              (req, res) -> seenDuringRequest[0] = MDC.get(TraceIdMdcFilter.TRACE_ID_MDC_KEY));
    }

    assertEquals(
        span.getSpanContext().getTraceId(),
        seenDuringRequest[0],
        "the real trace id must be visible on the MDC while the request is handled");
    assertNull(
        MDC.get(TraceIdMdcFilter.TRACE_ID_MDC_KEY), "and cleared once the request completes");
  }

  @Test
  void stampsTheEdgeRequestIdOnTheActiveSpanSoTheTraceIsSearchableByIt() throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    OpenTelemetrySdk exporting =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .setSampler(Sampler.alwaysOn())
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
    Span span = exporting.getTracer("test").spanBuilder("server").startSpan();
    MDC.put(TraceIdMdcFilter.REQUEST_ID_MDC_KEY, "req-abc-123");

    try (Scope ignored = span.makeCurrent()) {
      new TraceIdMdcFilter()
          .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});
    } finally {
      span.end();
      MDC.remove(TraceIdMdcFilter.REQUEST_ID_MDC_KEY);
    }

    assertEquals(1, exporter.getFinishedSpanItems().size());
    assertEquals(
        "req-abc-123",
        exporter
            .getFinishedSpanItems()
            .get(0)
            .getAttributes()
            .get(AttributeKey.stringKey(TraceIdMdcFilter.REQUEST_ID_ATTRIBUTE)),
        "the edge request id must be stamped on the server span so SigNoz can be searched by it");
  }

  @Test
  void putsNothingWhenNoSpanIsActive() throws Exception {
    String[] seenDuringRequest = {"sentinel"};

    new TraceIdMdcFilter()
        .doFilter(
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            (req, res) -> seenDuringRequest[0] = MDC.get(TraceIdMdcFilter.TRACE_ID_MDC_KEY));

    assertNull(seenDuringRequest[0], "no active span means no trace id on the MDC");
  }
}
