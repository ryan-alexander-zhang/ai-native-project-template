package com.aipersimmon.ddd.web.store.jdbc;

import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTemplate-backed fixed-window {@link RateLimiter}: per (key, window) counters live in {@code
 * aipersimmon_web_rate_limit}, shared across instances. Adequate but not optimal — fixed windows
 * permit boundary bursts and the increment is not a single atomic statement across all databases;
 * prefer the Redis backend under high concurrency.
 */
public class JdbcRateLimiter implements RateLimiter {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcRateLimiter(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public Decision tryAcquire(String key, RateLimitPolicy policy) {
    long windowMillis = policy.window().toMillis();
    long nowMillis = clock.millis();
    long alignedStart = (nowMillis / windowMillis) * windowMillis;
    Timestamp windowStart = new Timestamp(alignedStart);

    jdbc.update(
        "DELETE FROM aipersimmon_web_rate_limit WHERE bucket_key = ? AND window_start < ?",
        key,
        windowStart);

    int updated =
        jdbc.update(
            "UPDATE aipersimmon_web_rate_limit SET count = count + 1 WHERE bucket_key = ? AND window_start = ?",
            key,
            windowStart);
    if (updated == 0) {
      try {
        jdbc.update(
            "INSERT INTO aipersimmon_web_rate_limit (bucket_key, window_start, count) VALUES (?, ?, 1)",
            key,
            windowStart);
      } catch (DuplicateKeyException e) {
        jdbc.update(
            "UPDATE aipersimmon_web_rate_limit SET count = count + 1 "
                + "WHERE bucket_key = ? AND window_start = ?",
            key,
            windowStart);
      }
    }

    Long count =
        jdbc.queryForObject(
            "SELECT count FROM aipersimmon_web_rate_limit WHERE bucket_key = ? AND window_start = ?",
            Long.class,
            key,
            windowStart);
    long used = count == null ? 1 : count;

    Instant resetAt = Instant.ofEpochMilli(alignedStart + windowMillis);
    boolean allowed = used <= policy.limit();
    long remaining = Math.max(0, policy.limit() - used);
    Duration retryAfter =
        allowed ? Duration.ZERO : Duration.ofMillis(alignedStart + windowMillis - nowMillis);
    return new Decision(allowed, remaining, resetAt, retryAfter);
  }
}
