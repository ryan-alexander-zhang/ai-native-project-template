package com.aipersimmon.ddd.web.store.redis;

import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed fixed-window {@link RateLimiter}: a per-(key, window) counter is
 * incremented with {@code INCR} and given the window as its TTL on first hit, so
 * counting is atomic and shared across instances. Fixed windows still allow
 * boundary bursts, but the increment itself is race-free.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final String PREFIX = "aipersimmon:web:rl:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, RateLimitPolicy policy) {
        long windowMillis = policy.window().toMillis();
        long nowMillis = clock.millis();
        long alignedStart = (nowMillis / windowMillis) * windowMillis;
        String windowKey = PREFIX + key + ":" + alignedStart;

        Long count = redis.opsForValue().increment(windowKey);
        long used = count == null ? 1 : count;
        if (used == 1) {
            redis.expire(windowKey, policy.window());
        }

        Instant resetAt = Instant.ofEpochMilli(alignedStart + windowMillis);
        boolean allowed = used <= policy.limit();
        long remaining = Math.max(0, policy.limit() - used);
        Duration retryAfter = allowed ? Duration.ZERO
                : Duration.ofMillis(alignedStart + windowMillis - nowMillis);
        return new Decision(allowed, remaining, resetAt, retryAfter);
    }
}
