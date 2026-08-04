package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** A ticket that costs nothing is not an order this flow should be started for. */
record AmountIsPositive(long amountMinor) implements Invariant {

  @Override
  public boolean isBroken() {
    return amountMinor <= 0;
  }

  @Override
  public String message() {
    return "a ticket order's amount must be greater than zero";
  }

  @Override
  public ErrorCode errorCode() {
    return TicketingErrorCode.AMOUNT_NOT_POSITIVE;
  }
}
