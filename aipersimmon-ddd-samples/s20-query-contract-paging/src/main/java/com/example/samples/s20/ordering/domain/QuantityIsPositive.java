package com.example.samples.s20.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** The only rule this sample's write side needs; modelling is S16, this one is about reading. */
record QuantityIsPositive(int quantity) implements Invariant {

  @Override
  public boolean isBroken() {
    return quantity <= 0;
  }

  @Override
  public String message() {
    return "an order's quantity must be positive, was " + quantity;
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.QUANTITY_NOT_POSITIVE;
  }
}
