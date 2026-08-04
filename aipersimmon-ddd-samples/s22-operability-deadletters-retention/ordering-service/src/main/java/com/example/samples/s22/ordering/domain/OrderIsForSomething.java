package com.example.samples.s22.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** An order for nothing is not an order. */
record OrderIsForSomething(String sku, int quantity) implements Invariant {

  @Override
  public boolean isBroken() {
    return sku == null || sku.isBlank() || quantity <= 0;
  }

  @Override
  public String message() {
    return "an order needs a sku and a positive quantity";
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.ORDER_IS_FOR_NOTHING;
  }
}
