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
 * Establishes a trace id for each request: it reads the configured header, or
 * generates a UUID when absent (and generation is enabled), then exposes the id in
 * three places for the rest of the request — the SLF4J {@link MDC} (log
 * correlation), the response header (so the caller can quote it), and, via the
 * MDC, the exception advice that writes it into the problem body. The MDC entry is
 * always cleared when the request completes.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key under which the current trace id is stored. */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private final String header;
    private final boolean generateIfAbsent;

    public TraceIdFilter(String header, boolean generateIfAbsent) {
        this.header = header;
        this.generateIfAbsent = generateIfAbsent;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(header);
        if ((traceId == null || traceId.isBlank()) && generateIfAbsent) {
            traceId = UUID.randomUUID().toString();
        }
        boolean present = traceId != null && !traceId.isBlank();
        if (present) {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
            response.setHeader(header, traceId);
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
