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
 * Puts the active OpenTelemetry trace id (32-hex {@code trace_id}) on the SLF4J MDC for the
 * duration of each request, so the web layer's problem-detail writers can surface a real,
 * lookup-able trace id in error responses (alongside the edge {@code requestId}). Runs after
 * the OTEL server span is established, reads {@code Span.current()}, and clears the MDC entry
 * in a finally. When OpenTelemetry is not installed this filter is simply absent, and the
 * error body carries only the request id.
 */
public class TraceIdMdcFilter extends OncePerRequestFilter {

    /** MDC key the web problem-detail writers read for the real trace id. Matches web-spring. */
    static final String TRACE_ID_MDC_KEY = "trace_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SpanContext spanContext = Span.current().getSpanContext();
        boolean present = spanContext.isValid();
        if (present) {
            MDC.put(TRACE_ID_MDC_KEY, spanContext.getTraceId());
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
