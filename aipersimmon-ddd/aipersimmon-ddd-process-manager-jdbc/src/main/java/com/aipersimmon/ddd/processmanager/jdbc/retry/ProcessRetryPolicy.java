package com.aipersimmon.ddd.processmanager.jdbc.retry;

import java.time.Duration;

/**
 * Bounds redelivery of a failing effect or deadline: how long to wait before attempt {@code
 * attempt}, and the maximum number of attempts before it is dead-lettered. There is no unbounded
 * hot retry.
 */
public interface ProcessRetryPolicy {

  /** The backoff before the given (1-based) attempt number. */
  Duration backoff(int attempt);

  /** The attempt count at which the effect/deadline moves to DEAD. */
  int maxAttempts();
}
