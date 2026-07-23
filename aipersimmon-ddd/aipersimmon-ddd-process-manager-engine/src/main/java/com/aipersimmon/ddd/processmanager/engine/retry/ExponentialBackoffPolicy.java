package com.aipersimmon.ddd.processmanager.engine.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with a ceiling and jitter, capped by a maximum attempt count. The base wait
 * for attempt {@code n} is {@code min(max, initial * multiplier^(n-1))}, scaled by {@code 1 ±
 * jitter} to avoid a thundering herd. The random source is injectable so tests can be deterministic
 * (pass a fixed supplier, or jitter 0).
 */
public final class ExponentialBackoffPolicy implements ProcessRetryPolicy {

  private final Duration initial;
  private final Duration max;
  private final double multiplier;
  private final double jitter;
  private final int maxAttempts;
  private final DoubleSupplier randomizer;

  public ExponentialBackoffPolicy(
      Duration initial, Duration max, double multiplier, double jitter, int maxAttempts) {
    this(
        initial,
        max,
        multiplier,
        jitter,
        maxAttempts,
        () -> ThreadLocalRandom.current().nextDouble());
  }

  public ExponentialBackoffPolicy(
      Duration initial,
      Duration max,
      double multiplier,
      double jitter,
      int maxAttempts,
      DoubleSupplier randomizer) {
    this.initial = validInitial(initial);
    this.max = validMax(max, this.initial);
    this.multiplier = validMultiplier(multiplier);
    this.jitter = validJitter(jitter);
    this.maxAttempts = validMaxAttempts(maxAttempts);
    this.randomizer = randomizer;
  }

  private static Duration validInitial(Duration initial) {
    if (initial == null || initial.isNegative() || initial.isZero()) {
      throw new IllegalArgumentException("initial backoff must be positive");
    }
    if (initial.toMillis() == 0) {
      // The backoff computes in milliseconds; a sub-millisecond initial would truncate to a
      // zero delay and degenerate into a hot retry loop. Require at least 1ms.
      throw new IllegalArgumentException("initial backoff must be at least 1ms");
    }
    return initial;
  }

  private static Duration validMax(Duration max, Duration initial) {
    if (max == null || max.compareTo(initial) < 0) {
      throw new IllegalArgumentException("max backoff must be >= initial");
    }
    return max;
  }

  private static double validMultiplier(double multiplier) {
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be >= 1.0");
    }
    return multiplier;
  }

  private static double validJitter(double jitter) {
    if (jitter < 0.0 || jitter > 1.0) {
      throw new IllegalArgumentException("jitter must be within [0, 1]");
    }
    return jitter;
  }

  private static int validMaxAttempts(int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    return maxAttempts;
  }

  @Override
  public Duration backoff(int attempt) {
    int exponent = Math.max(0, attempt - 1);
    double base = initial.toMillis() * Math.pow(multiplier, exponent);
    double capped = Math.min(base, (double) max.toMillis());
    double factor = 1.0 + jitter * (2.0 * randomizer.getAsDouble() - 1.0);
    long millis = Math.max(0L, Math.round(capped * factor));
    return Duration.ofMillis(millis);
  }

  @Override
  public int maxAttempts() {
    return maxAttempts;
  }
}
