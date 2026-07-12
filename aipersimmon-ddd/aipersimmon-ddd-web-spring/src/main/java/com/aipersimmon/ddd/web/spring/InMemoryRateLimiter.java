package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window {@link RateLimiter} for single-node and development use.
 * Counts requests per key within aligned windows. Fixed windows are simple but
 * allow bursts at window boundaries; for production (and multi-instance) use a
 * store backend module, which replaces this bean with a shared implementation.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private static final class Window {
        private long startMillis;
        private long count;
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, RateLimitPolicy policy) {
        long windowMillis = policy.window().toMillis();
        long nowMillis = clock.millis();
        long alignedStart = (nowMillis / windowMillis) * windowMillis;

        Window window = windows.computeIfAbsent(key, k -> new Window());
        long count;
        synchronized (window) {
            if (window.startMillis != alignedStart) {
                window.startMillis = alignedStart;
                window.count = 0;
            }
            window.count++;
            count = window.count;
        }

        Instant resetAt = Instant.ofEpochMilli(alignedStart + windowMillis);
        boolean allowed = count <= policy.limit();
        long remaining = Math.max(0, policy.limit() - count);
        Duration retryAfter = allowed ? Duration.ZERO
                : Duration.ofMillis(alignedStart + windowMillis - nowMillis);
        return new Decision(allowed, remaining, resetAt, retryAfter);
    }
}
