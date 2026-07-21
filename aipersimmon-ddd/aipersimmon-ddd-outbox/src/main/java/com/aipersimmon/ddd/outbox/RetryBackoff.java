package com.aipersimmon.ddd.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Computes how long to wait before the next delivery attempt: capped exponential growth with
 * jitter. The cap doubles per attempt ({@code base * 2^(attempts-1)}) up to a ceiling, so a
 * persistently failing message backs off instead of hammering the broker every poll (the flaw this
 * replaces: a fixed retry with no spacing). Jitter (equal jitter — half the cap plus a random half)
 * spreads the retries of many messages that failed together, so they do not all re-attempt in
 * lockstep and stampede a recovering broker.
 *
 * <p>Immutable and thread-safe; randomness comes from {@link ThreadLocalRandom}.
 */
public final class RetryBackoff {

  private final long baseMillis;
  private final long maxMillis;

  /**
   * @param baseMillis the first attempt's backoff cap (and the growth base), {@code >= 0}
   * @param maxMillis the backoff ceiling; raised to {@code baseMillis} if smaller
   */
  public RetryBackoff(long baseMillis, long maxMillis) {
    if (baseMillis < 0 || maxMillis < 0) {
      throw new IllegalArgumentException("backoff bounds must be non-negative");
    }
    this.baseMillis = baseMillis;
    this.maxMillis = Math.max(baseMillis, maxMillis);
  }

  /**
   * The delay before attempt number {@code attempts + 1}, given {@code attempts} have already
   * failed. Returns a value in {@code [cap/2, cap]} where {@code cap = min(maxMillis, baseMillis *
   * 2^(attempts-1))}.
   *
   * @param attempts how many attempts have failed so far ({@code >= 1})
   */
  public Duration nextDelay(int attempts) {
    int exponent = Math.max(0, attempts - 1);
    long cap;
    if (exponent >= 62) {
      cap = maxMillis;
    } else {
      long scaled = baseMillis << exponent;
      cap = (scaled < 0 || scaled > maxMillis) ? maxMillis : scaled;
    }
    long half = cap / 2;
    long jitter = half == 0 ? 0 : ThreadLocalRandom.current().nextLong(half + 1);
    return Duration.ofMillis(half + jitter);
  }
}
