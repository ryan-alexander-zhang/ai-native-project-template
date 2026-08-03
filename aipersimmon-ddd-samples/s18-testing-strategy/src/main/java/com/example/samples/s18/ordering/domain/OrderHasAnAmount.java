package com.example.samples.s18.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** The rule the domain unit test exercises without any framework in sight. */
record OrderHasAnAmount(long amountCents) implements Invariant {

  @Override
  public boolean isBroken() {
    return amountCents <= 0;
  }

  @Override
  public String message() {
    return "an order must have a positive amount";
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.ORDER_HAS_NO_AMOUNT;
  }
}
