package com.example.samples.s19.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * The third layer. This one is not advisory: it is the rule that must hold of any order that exists,
 * and it is checked where the state is about to be written.
 */
record QuantityWithinCap(int quantity, int cap) implements Invariant {

  @Override
  public boolean isBroken() {
    return quantity > cap;
  }

  @Override
  public String message() {
    return "an order may not exceed " + cap + " units, was " + quantity;
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.QUANTITY_OVER_CAP;
  }
}
