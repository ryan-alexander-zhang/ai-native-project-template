package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Makes an authorised write safe to retry: the first request under an idempotency
 * key runs and its response is stored; a later request with the same key replays
 * that stored response instead of executing again. Applies only to the configured
 * methods and only when the key header is present (a missing key is a 400 when
 * {@code require-key} is set, otherwise it passes through). This is a reliability
 * concern — distinct from replay protection.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyStore store;
    private final ProblemHttpResponseWriter problemWriter;
    private final String header;
    private final Duration ttl;
    private final boolean requireKey;
    private final Set<String> methods;

    public IdempotencyFilter(IdempotencyStore store, ProblemHttpResponseWriter problemWriter,
                             String header, Duration ttl, boolean requireKey, Set<String> methods) {
        this.store = store;
        this.problemWriter = problemWriter;
        this.header = header;
        this.ttl = ttl;
        this.requireKey = requireKey;
        this.methods = methods;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!methods.contains(request.getMethod().toUpperCase())) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(header);
        if (key == null || key.isBlank()) {
            if (requireKey) {
                problemWriter.write(response, HttpStatus.BAD_REQUEST, "/problems/idempotency-key-required",
                        "Missing " + header + " header", Map.of());
            } else {
                filterChain.doFilter(request, response);
            }
            return;
        }

        Optional<StoredResponse> replay = store.find(key);
        if (replay.isPresent()) {
            writeStored(response, replay.get());
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);

        byte[] body = wrapper.getContentAsByteArray();
        String contentType = wrapper.getContentType();
        Map<String, String> headers = contentType == null ? Map.of()
                : Map.of(HttpHeaders.CONTENT_TYPE, contentType);
        store.saveIfAbsent(key, new StoredResponse(wrapper.getStatus(), body, headers), ttl);
        wrapper.copyBodyToResponse();
    }

    private void writeStored(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        stored.headers().forEach(response::setHeader);
        response.getOutputStream().write(stored.body());
    }
}
