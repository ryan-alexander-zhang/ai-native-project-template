package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import java.util.List;

/** An order with nothing on it must never be placed: an assertion, so an invariant. */
record OrderHasAtLeastOneLine(List<OrderLine> lines) implements Invariant {

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
