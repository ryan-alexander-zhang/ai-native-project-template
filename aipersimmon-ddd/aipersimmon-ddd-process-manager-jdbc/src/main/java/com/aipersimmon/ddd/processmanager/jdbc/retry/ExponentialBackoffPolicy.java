package com.aipersimmon.ddd.processmanager.jdbc.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with a ceiling and jitter, capped by a maximum attempt count. The
 * base wait for attempt {@code n} is {@code min(max, initial * multiplier^(n-1))},
 * scaled by {@code 1 ± jitter} to avoid a thundering herd. The random source is
 * injectable so tests can be deterministic (pass a fixed supplier, or jitter 0).
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
        this(initial, max, multiplier, jitter, maxAttempts,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    public ExponentialBackoffPolicy(
            Duration initial, Duration max, double multiplier, double jitter, int maxAttempts,
            DoubleSupplier randomizer) {
        if (initial == null || initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial backoff must be positive");
        }
        if (initial.toMillis() == 0) {
            // The backoff computes in milliseconds; a sub-millisecond initial would truncate to a
            // zero delay and degenerate into a hot retry loop. Require at least 1ms.
            throw new IllegalArgumentException("initial backoff must be at least 1ms");
        }
        if (max == null || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max backoff must be >= initial");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be within [0, 1]");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.initial = initial;
        this.max = max;
        this.multiplier = multiplier;
        this.jitter = jitter;
        this.maxAttempts = maxAttempts;
        this.randomizer = randomizer;
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
