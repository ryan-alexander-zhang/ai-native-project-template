package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Random;

/**
 * The control for {@code domainShouldNotUseAmbientTimeOrRandomness}: every call here reads its
 * value from an argument rather than from the environment, and none of them may be reported.
 *
 * <p>It sits in the {@code bad} package on purpose. That package is where the rule is measured, so
 * if the condition had degenerated into "touches {@code java.time}", "constructs a {@code Date}" or
 * "constructs a {@code Random}", this class would appear in the same report as {@link
 * BadSelfStampingOrder} — and the test asserts it does not.
 */
public class BadInjectedTimeOrder {

  private final Clock clock;

  public BadInjectedTimeOrder(Clock clock) {
    this.clock = clock;
  }

  public Instant now() {
    return Instant.now(clock);
  }

  public LocalDate today() {
    return LocalDate.now(clock);
  }

  public Date stampOf(long epochMillis) {
    return new Date(epochMillis);
  }

  public Random seeded(long seed) {
    return new Random(seed);
  }
}
