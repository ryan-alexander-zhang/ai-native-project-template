package com.example.samples.s07.payments.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * A payment moves money, so zero is not a payment and a negative amount is a refund — a different
 * operation with different authorisation. Checked in the aggregate rather than only at the edge because
 * the reconciler and the callback path never pass through the edge.
 */
record AmountIsPositive(long amountMinor) implements Invariant {

  @Override
  public boolean isBroken() {
    return amountMinor <= 0;
  }

  @Override
  public String message() {
    return "a payment amount must be greater than zero";
  }

  @Override
  public ErrorCode errorCode() {
    return PaymentsErrorCode.AMOUNT_NOT_POSITIVE;
  }
}
