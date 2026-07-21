package com.aipersimmon.ddd.web.spi;

import java.time.Duration;

/**
 * A rate-limit quota: at most {@code limit} requests per {@code window}, under a named policy. The
 * name is echoed in the {@code RateLimit}/{@code RateLimit-Policy} response headers so a client can
 * tell which quota it hit.
 *
 * @param name policy name, e.g. {@code "default"}
 * @param limit maximum number of requests allowed within the window
 * @param window the rolling/fixed window length
 */
public record RateLimitPolicy(String name, long limit, Duration window) {

  public RateLimitPolicy {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("policy name must not be blank");
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, was " + limit);
    }
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("window must be positive");
    }
  }
}
