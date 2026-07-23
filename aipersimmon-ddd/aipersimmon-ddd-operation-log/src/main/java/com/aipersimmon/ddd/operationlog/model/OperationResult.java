package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * The two orthogonal dimensions of an operation's result — its business {@link Outcome} and its
 * transaction {@link Completion} — carried together as the "result kind" that keys idempotency.
 */
@ValueObject
public record OperationResult(Outcome outcome, Completion completion) {

  /**
   * @throws NullPointerException if either dimension is null
   */
  public OperationResult {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(completion, "completion");
  }

  /** A result kind from its two dimensions. */
  public static OperationResult of(Outcome outcome, Completion completion) {
    return new OperationResult(outcome, completion);
  }
}
