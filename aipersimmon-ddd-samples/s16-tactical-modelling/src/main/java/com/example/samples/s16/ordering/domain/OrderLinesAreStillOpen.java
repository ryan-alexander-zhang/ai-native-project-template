package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * Lines may only change while the order is a draft. Named and reused by both {@code addLine} and
 * {@code amendLine} — which is exactly when a rule earns being an {@link Invariant} instead of an
 * inline throw.
 */
record OrderLinesAreStillOpen(OrderStatus status) implements Invariant {

  @Override
  public boolean isBroken() {
    return status != OrderStatus.DRAFT;
  }

  @Override
  public String message() {
    return "the lines of a " + status + " order cannot be changed";
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.ORDER_LINES_FROZEN;
  }
}
