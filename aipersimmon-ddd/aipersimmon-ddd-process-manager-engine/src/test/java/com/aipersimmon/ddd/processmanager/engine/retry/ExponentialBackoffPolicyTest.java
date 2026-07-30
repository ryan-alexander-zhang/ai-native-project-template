package com.aipersimmon.ddd.processmanager.engine.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/**
 * How a failed effect or deadline is spaced out before the next attempt. Retrying a whole fleet in
 * lockstep against a service that has just come back is how a recovery turns into a second outage,
 * so the spacing and its jitter are the behaviour, not an implementation detail.
 */
class ExponentialBackoffPolicyTest {

  /** No jitter, so the schedule itself can be asserted. */
  private static final DoubleSupplier MIDPOINT = () -> 0.5;

  private ExponentialBackoffPolicy policy(double jitter, DoubleSupplier randomizer) {
    return new ExponentialBackoffPolicy(
        Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, jitter, 5, randomizer);
  }

  @Test
  void theWaitDoublesWithEachAttempt() {
    ExponentialBackoffPolicy policy = policy(0.0, MIDPOINT);

    assertEquals(Duration.ofMillis(100), policy.backoff(1));
    assertEquals(Duration.ofMillis(200), policy.backoff(2));
    assertEquals(Duration.ofMillis(400), policy.backoff(3));
    assertEquals(Duration.ofMillis(800), policy.backoff(4));
  }

  @Test
  void theWaitStopsGrowingAtTheCeiling() {
    ExponentialBackoffPolicy policy = policy(0.0, MIDPOINT);

    assertEquals(
        Duration.ofSeconds(10),
        policy.backoff(20),
        "without a ceiling, doubling reaches days — a transient fault would look permanent to "
            + "anyone waiting on the process");
  }

  @Test
  void theFirstAttemptWaitsTheInitialRatherThanScalingBelowIt() {
    ExponentialBackoffPolicy policy = policy(0.0, MIDPOINT);

    assertEquals(Duration.ofMillis(100), policy.backoff(0), "attempt 0 is treated as the first");
    assertEquals(Duration.ofMillis(100), policy.backoff(1));
  }

  @Test
  void jitterSpreadsRetriesEitherSideOfTheScheduledWait() {
    assertEquals(
        Duration.ofMillis(50),
        policy(0.5, () -> 0.0).backoff(1),
        "the low end of a 50% jitter band");
    assertEquals(
        Duration.ofMillis(150),
        policy(0.5, () -> 1.0).backoff(1),
        "and the high end — a fleet that failed together does not retry together");
    assertEquals(Duration.ofMillis(100), policy(0.5, MIDPOINT).backoff(1));
  }

  @Test
  void aWaitIsNeverNegativeHoweverTheJitterFalls() {
    assertTrue(!policy(1.0, () -> 0.0).backoff(1).isNegative());
  }

  @Test
  void aSubMillisecondInitialIsRefusedRatherThanTruncatedToAHotLoop() {
    // The schedule computes in milliseconds, so anything below one would round to no wait at all
    // and the retry would become a spin.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExponentialBackoffPolicy(
                Duration.ofNanos(500), Duration.ofSeconds(1), 2.0, 0.0, 3));
  }

  @Test
  void aScheduleThatCouldNotSpaceAnythingOutIsRefusedAtConstruction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExponentialBackoffPolicy(Duration.ZERO, Duration.ofSeconds(1), 2.0, 0.0, 3),
        "a zero initial is a hot loop");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExponentialBackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 2.0, 0.0, 3),
        "a ceiling below the initial would shorten the first wait rather than cap the last");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExponentialBackoffPolicy(Duration.ofMillis(1), Duration.ofSeconds(1), 0.5, 0.0, 3),
        "a multiplier below one shrinks the wait with every failure");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExponentialBackoffPolicy(Duration.ofMillis(1), Duration.ofSeconds(1), 2.0, 1.5, 3),
        "jitter beyond the whole wait could push an attempt before the previous one");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExponentialBackoffPolicy(Duration.ofMillis(1), Duration.ofSeconds(1), 2.0, 0.0, 0),
        "a policy that permits no attempts would abandon every effect on its first failure");
  }

  @Test
  void theAttemptBudgetIsWhatTheCallerConfigured() {
    assertEquals(5, policy(0.0, MIDPOINT).maxAttempts());
  }

  @Test
  void theDefaultRandomSourceStillProducesAWaitInsideTheJitterBand() {
    ExponentialBackoffPolicy policy =
        new ExponentialBackoffPolicy(Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.5, 5);

    Duration wait = policy.backoff(1);

    assertTrue(
        wait.compareTo(Duration.ofMillis(50)) >= 0 && wait.compareTo(Duration.ofMillis(150)) <= 0,
        "was: " + wait);
  }
}
