package com.aipersimmon.ddd.observability.otel.autoconfigure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bridges the edge request id and the OpenTelemetry trace, in both directions, for the duration of
 * each request — so an operator can pivot between them:
 *
 * <ul>
 *   <li><strong>trace id → MDC</strong>: puts the active 32-hex {@code trace_id} on the SLF4J MDC,
 *       so the web layer's problem-detail writers can surface a real, lookup-able trace id in error
 *       responses (alongside the edge {@code requestId}), and so log lines can carry it.
 *   <li><strong>request id → span</strong>: stamps the edge request id (the {@code X-Request-Id}
 *       the caller was handed — client-supplied or server-generated, already resolved onto the MDC
 *       by web-spring's {@code RequestIdFilter}) onto the active server span as the {@code
 *       request.id} attribute, so the trace is <em>searchable by the id the caller holds</em>. This
 *       is the correlation-id pattern: a caller-facing id that is not the trace id must be indexed
 *       on the trace to be found; reading the resolved MDC value (rather than only capturing an
 *       inbound header) also covers the server-generated case, where no {@code X-Request-Id} header
 *       arrived. It is intentionally distinct from the messaging-layer {@code correlation.id} the
 *       command interceptor stamps.
 * </ul>
 *
 * <p>Runs after the OTEL server span is established (it reads {@code Span.current()}) and after the
 * request id is on the MDC, and clears its MDC entry in a finally. When OpenTelemetry is not
 * installed this filter is simply absent, and the error body carries only the request id.
 */
public class TraceIdMdcFilter extends OncePerRequestFilter {

  /** MDC key the web problem-detail writers read for the real trace id. Matches web-spring. */
  static final String TRACE_ID_MDC_KEY = "trace_id";

  /** MDC key the edge request id is published under by web-spring's {@code RequestIdFilter}. */
  static final String REQUEST_ID_MDC_KEY = "requestId";

  /** Span attribute carrying the edge request id, so a trace is searchable by the caller's id. */
  static final String REQUEST_ID_ATTRIBUTE = "request.id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Span span = Span.current();
    SpanContext spanContext = span.getSpanContext();
    boolean present = spanContext.isValid();
    if (present) {
      MDC.put(TRACE_ID_MDC_KEY, spanContext.getTraceId());
      String requestId = MDC.get(REQUEST_ID_MDC_KEY);
      if (requestId != null && !requestId.isBlank()) {
        span.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
      }
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (present) {
        MDC.remove(TRACE_ID_MDC_KEY);
      }
    }
  }
}
