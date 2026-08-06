package com.aipersimmon.ddd.web.store.mybatisplus;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatis-Plus-backed fixed-window {@link RateLimiter}: per (key, window) counters live in {@code
 * aipersimmon_web_rate_limit}, shared across instances. Adequate but not optimal — fixed windows
 * permit boundary bursts and the increment is not a single atomic statement across all databases;
 * prefer the Redis backend under high concurrency.
 */
public class MybatisPlusRateLimiter implements RateLimiter {

  private final RateLimitMapper mapper;
  private final Clock clock;

  public MybatisPlusRateLimiter(RateLimitMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @Override
  public Decision tryAcquire(String key, RateLimitPolicy policy) {
    long windowMillis = policy.window().toMillis();
    long nowMillis = clock.millis();
    long alignedStart = (nowMillis / windowMillis) * windowMillis;
    Instant windowStart = Instant.ofEpochMilli(alignedStart);
    String tenant = tenant();

    // Sweep this bucket's expired windows, but only ones old enough that nobody can still be
    // counting in them. Deleting everything before the caller's own window would turn the ordinary
    // boundary crossing into a fault: two requests on one bucket a millisecond apart land in
    // different windows, the later one deletes the earlier one's row, and the earlier one's read
    // below then finds nothing. One window of slack is enough to make that impossible — two is what
    // is used, because the cost of slack is one inert row and the cost of being wrong is a 500. A
    // caller more than two windows behind is counting in a window that expired long ago.
    mapper.delete(
        bucket(tenant, key)
            .lt(RateLimitRecord::getWindowStart, windowStart.minusMillis(2 * windowMillis)));

    if (mapper.increment(tenant, key, windowStart) == 0) {
      try {
        mapper.insert(new RateLimitRecord(tenant, key, windowStart, 1L));
      } catch (DuplicateKeyException raced) {
        // Another instance opened this window between our failed increment and our insert; its row
        // is the one to count in.
        mapper.increment(tenant, key, windowStart);
      }
    }

    // No row here means the counter this call just incremented has been swept — a retention job, or
    // an operator. Reading through a "must return exactly one" query would turn that into a 500 for
    // a caller who was never over their limit. Answer with this call's own increment instead: a
    // rate limiter that loses its counter should let the request through, not fail it.
    long used =
        mapper
            .selectList(
                bucket(tenant, key)
                    .select(RateLimitRecord::getCount)
                    .eq(RateLimitRecord::getWindowStart, windowStart))
            .stream()
            .findFirst()
            .map(RateLimitRecord::getCount)
            .orElse(1L);

    Instant resetAt = Instant.ofEpochMilli(alignedStart + windowMillis);
    boolean allowed = used <= policy.limit();
    long remaining = Math.max(0, policy.limit() - used);
    Duration retryAfter =
        allowed ? Duration.ZERO : Duration.ofMillis(alignedStart + windowMillis - nowMillis);
    return new Decision(allowed, remaining, resetAt, retryAfter);
  }

  private static LambdaQueryWrapper<RateLimitRecord> bucket(String tenant, String key) {
    return new LambdaQueryWrapper<RateLimitRecord>()
        .eq(RateLimitRecord::getTenantId, tenant)
        .eq(RateLimitRecord::getBucketKey, key);
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
