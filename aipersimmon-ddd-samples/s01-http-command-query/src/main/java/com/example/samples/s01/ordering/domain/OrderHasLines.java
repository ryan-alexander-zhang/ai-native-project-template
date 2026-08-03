package com.example.samples.s01.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import java.util.List;

/**
 * An invariant, not a specification: an order with no lines is not an ordinary outcome to branch on,
 * it is a state that must never be written. That is why it carries an {@link ErrorCode} — the
 * violation travels to the edge.
 */
record OrderHasLines(List<OrderLine> lines) implements Invariant {

  @Override
  public boolean isBroken() {
    return lines.isEmpty();
  }

  @Override
  public String message() {
    return "an order must have at least one line";
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.ORDER_HAS_NO_LINES;
  }
}
