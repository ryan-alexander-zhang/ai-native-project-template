package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a rate-limit policy per bucket key (client IP or a header). When quota
 * remains the request proceeds with {@code RateLimit} headers advertising the
 * remaining allowance; when exhausted it is rejected with {@code 429}, a
 * {@code Retry-After} header, and a problem body — following RFC 6585 / RFC 9110
 * and the IETF RateLimit header draft.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ProblemHttpResponseWriter problemWriter;
    private final AipersimmonDddWebProperties.RateLimit config;
    private final RateLimitPolicy policy;

    public RateLimitFilter(RateLimiter rateLimiter, ProblemHttpResponseWriter problemWriter,
                           AipersimmonDddWebProperties.RateLimit config) {
        this.rateLimiter = rateLimiter;
        this.problemWriter = problemWriter;
        this.config = config;
        this.policy = new RateLimitPolicy(config.getPolicyName(), config.getLimit(), config.getWindow());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(bucketKey(request), policy);

        if (!decision.allowed()) {
            long retryAfterSeconds = Math.max(1, decision.retryAfter().toSeconds());
            Map<String, String> headers = rateLimitHeaders(decision);
            headers.put(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            problemWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "/problems/rate-limited",
                    "Rate limit exceeded", headers);
            return;
        }

        rateLimitHeaders(decision).forEach(response::setHeader);
        filterChain.doFilter(request, response);
    }

    private String bucketKey(HttpServletRequest request) {
        if ("header".equalsIgnoreCase(config.getKey())) {
            String value = request.getHeader(config.getKeyHeader());
            return value == null ? "anonymous" : value;
        }
        return request.getRemoteAddr();
    }

    private Map<String, String> rateLimitHeaders(RateLimiter.Decision decision) {
        Map<String, String> headers = new LinkedHashMap<>();
        String mode = config.getHeaders();
        long resetSeconds = Math.max(0, decision.resetAt().getEpochSecond() - System.currentTimeMillis() / 1000);
        if ("ietf".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode)) {
            headers.put("RateLimit", "\"" + policy.name() + "\";r=" + decision.remaining() + ";t=" + resetSeconds);
            headers.put("RateLimit-Policy", "\"" + policy.name() + "\";q=" + policy.limit()
                    + ";w=" + policy.window().toSeconds());
        }
        if ("legacy".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode)) {
            headers.put("X-RateLimit-Limit", Long.toString(policy.limit()));
            headers.put("X-RateLimit-Remaining", Long.toString(decision.remaining()));
            headers.put("X-RateLimit-Reset", Long.toString(decision.resetAt().getEpochSecond()));
        }
        return headers;
    }
}
