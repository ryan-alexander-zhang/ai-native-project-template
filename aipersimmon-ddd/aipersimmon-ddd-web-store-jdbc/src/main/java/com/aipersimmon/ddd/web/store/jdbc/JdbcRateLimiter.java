package com.aipersimmon.ddd.web.store.jdbc;

import com.aipersimmon.ddd.tenancy.TenantContext;
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
    String tenant = tenant();

    // Sweep this bucket's expired windows, but only ones old enough that nobody can still be
    // counting in them. It used to delete everything before the caller's own window, which turned
    // the ordinary boundary crossing into a fault: two requests on one bucket a millisecond apart
    // land in different windows, the later one deletes the earlier one's row, and the earlier one's
    // read below then finds nothing. One window of slack is enough to make that impossible — two is
    // what is used, because the cost of slack is one inert row and the cost of being wrong is a
    // 500. A caller more than two windows behind is counting in a window that expired long ago.
    jdbc.update(
        "DELETE FROM aipersimmon_web_rate_limit WHERE tenant_id = ? AND bucket_key = ? AND window_start < ?",
        tenant,
        key,
        new Timestamp(alignedStart - 2 * windowMillis));

    int updated =
        jdbc.update(
            "UPDATE aipersimmon_web_rate_limit SET count = count + 1 "
                + "WHERE tenant_id = ? AND bucket_key = ? AND window_start = ?",
            tenant,
            key,
            windowStart);
    if (updated == 0) {
      try {
        jdbc.update(
            "INSERT INTO aipersimmon_web_rate_limit (tenant_id, bucket_key, window_start, count) VALUES (?, ?, ?, 1)",
            tenant,
            key,
            windowStart);
      } catch (DuplicateKeyException e) {
        jdbc.update(
            "UPDATE aipersimmon_web_rate_limit SET count = count + 1 "
                + "WHERE tenant_id = ? AND bucket_key = ? AND window_start = ?",
            tenant,
            key,
            windowStart);
      }
    }

    // No row here means the counter this call just incremented has been swept — a retention job, or
    // an operator. queryForObject would raise EmptyResultDataAccessException and turn that into a
    // 500 on a request that was never over its limit; the old `count == null` guard did not help,
    // because it catches a null VALUE and this is an absent ROW. Answer with this call's own
    // increment: a rate limiter that loses its counter should let the request through, not fail it.
    long used =
        jdbc
            .query(
                "SELECT count FROM aipersimmon_web_rate_limit "
                    + "WHERE tenant_id = ? AND bucket_key = ? AND window_start = ?",
                (rs, n) -> rs.getLong("count"),
                tenant,
                key,
                windowStart)
            .stream()
            .findFirst()
            .orElse(1L);

    Instant resetAt = Instant.ofEpochMilli(alignedStart + windowMillis);
    boolean allowed = used <= policy.limit();
    long remaining = Math.max(0, policy.limit() - used);
    Duration retryAfter =
        allowed ? Duration.ZERO : Duration.ofMillis(alignedStart + windowMillis - nowMillis);
    return new Decision(allowed, remaining, resetAt, retryAfter);
  }

  /**
   * The tenant that scopes this bucket, read from the ambient {@link TenantContext} bound on the
   * request edge; the root sentinel when tenancy is off. The bucket key is caller-derived, so
   * tenant is part of its identity — see the composite primary key — and quota is never shared
   * across tenants.
   */
  private static String tenant() {
    return TenantContext.effective().value();
  }
}
