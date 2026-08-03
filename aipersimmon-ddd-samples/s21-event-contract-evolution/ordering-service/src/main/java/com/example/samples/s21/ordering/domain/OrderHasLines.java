package com.example.samples.s21.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import java.util.List;

/** The one rule here, and the reason a failed command must publish nothing. */
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
