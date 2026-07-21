package com.aipersimmon.ddd.web.spi;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a request may proceed under a {@link RateLimitPolicy}, consuming one unit of
 * quota for the given key (typically derived from client IP, user, or API key). A rejected decision
 * drives a {@code 429} response with {@code Retry-After} and {@code RateLimit} headers.
 */
public interface RateLimiter {

  /**
   * Attempts to consume one unit of quota for {@code key} under {@code policy}.
   *
   * @param key the bucket key (e.g. the client identifier)
   * @param policy the quota to apply
   * @return the decision, including remaining quota and reset timing
   */
  Decision tryAcquire(String key, RateLimitPolicy policy);

  /**
   * The outcome of a rate-limit check.
   *
   * @param allowed whether the request may proceed
   * @param remaining units of quota left in the current window
   * @param resetAt when the current window resets
   * @param retryAfter how long the caller should wait before retrying (meaningful when {@code
   *     allowed} is false); may be {@link Duration#ZERO}
   */
  record Decision(boolean allowed, long remaining, Instant resetAt, Duration retryAfter) {

    public Decision {
      if (retryAfter == null) {
        retryAfter = Duration.ZERO;
      }
    }
  }
}
