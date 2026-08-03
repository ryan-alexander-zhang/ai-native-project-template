package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * A ceiling the ordering context imposes on a single order. It reads only the order's own data, which
 * is why it can be an invariant of this aggregate at all — a rule about the customer's credit limit
 * would span two aggregates and belongs elsewhere (S8).
 */
record OrderTotalWithinCeiling(Money total, Money ceiling) implements Invariant {

  @Override
  public boolean isBroken() {
    return total.isGreaterThan(ceiling);
  }

  @Override
  public String message() {
    return "an order total of " + total.amount() + " exceeds the ceiling of " + ceiling.amount();
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.ORDER_TOTAL_EXCEEDS_CEILING;
  }
}
