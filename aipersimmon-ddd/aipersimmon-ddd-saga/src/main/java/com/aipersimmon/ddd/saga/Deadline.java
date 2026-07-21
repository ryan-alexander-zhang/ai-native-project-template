package com.aipersimmon.ddd.saga;

import java.time.Instant;
import java.util.Objects;

/**
 * A scheduled timeout for a saga: at {@link #fireAt()}, the saga identified by {@link
 * #correlationId()} should be called back with the deadline {@link #name()} so it can act on the
 * timeout (for example, compensate a reservation that was never confirmed). Names let one saga
 * register several distinct timeouts and cancel them individually once the awaited event arrives.
 *
 * @param correlationId the saga instance this deadline belongs to
 * @param name a saga-defined label distinguishing this timeout
 * @param fireAt the instant at which the deadline is due
 */
public record Deadline(String correlationId, String name, Instant fireAt) {

  public Deadline {
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(fireAt, "fireAt");
  }
}
