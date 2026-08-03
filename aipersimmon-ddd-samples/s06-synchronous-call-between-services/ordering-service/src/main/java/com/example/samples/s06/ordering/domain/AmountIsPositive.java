package com.example.samples.s06.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** The aggregate's own rule, which needs nobody's permission. */
record AmountIsPositive(long amountCents) implements Invariant {

  @Override
  public boolean isBroken() {
    return amountCents <= 0;
  }

  @Override
  public String message() {
    return "an order's amount must be positive, was " + amountCents;
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.NON_POSITIVE_AMOUNT;
  }
}
