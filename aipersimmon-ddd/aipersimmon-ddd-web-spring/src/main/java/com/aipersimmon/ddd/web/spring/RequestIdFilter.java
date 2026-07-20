package com.aipersimmon.ddd.web.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes a request id for each request: it reads the configured header, or
 * generates a UUID when absent (and generation is enabled), then exposes the id in
 * three places for the rest of the request — the SLF4J {@link MDC} (log correlation),
 * the response header (so the caller can quote it), and, via the MDC, the exception
 * advice that writes it into the problem body. The MDC entry is always cleared when
 * the request completes.
 *
 * <p>This is a per-request correlation id minted at the edge — distinct from the
 * distributed-trace id. The real trace id (a 32-hex OpenTelemetry {@code trace_id}) is
 * a separate concern: when the optional observability-otel module is present it puts the
 * active trace id under the {@link #TRACE_ID_MDC_KEY} MDC key, which the problem-detail
 * writers also surface; without it, only the request id is available.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    /** MDC key under which the current request id is stored. */
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    /**
     * MDC key under which the real OpenTelemetry trace id is stored, when the optional
     * observability-otel module is on the classpath. Absent otherwise.
     */
    public static final String TRACE_ID_MDC_KEY = "trace_id";

    private final String header;
    private final boolean generateIfAbsent;

    public RequestIdFilter(String header, boolean generateIfAbsent) {
        this.header = header;
        this.generateIfAbsent = generateIfAbsent;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(header);
        if ((requestId == null || requestId.isBlank()) && generateIfAbsent) {
            requestId = UUID.randomUUID().toString();
        }
        boolean present = requestId != null && !requestId.isBlank();
        if (present) {
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
            response.setHeader(header, requestId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (present) {
                MDC.remove(REQUEST_ID_MDC_KEY);
            }
        }
    }
}
