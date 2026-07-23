package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.time.Instant;
import java.util.Objects;

/**
 * The two timestamps of an entry: {@code occurredAt} (when the operation happened) and {@code
 * recordedAt} (when the component persisted it). Both are UTC instants.
 */
@ValueObject
public record EntryTimes(Instant occurredAt, Instant recordedAt) {

  /**
   * @throws NullPointerException if either instant is null
   */
  public EntryTimes {
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(recordedAt, "recordedAt");
  }
}
