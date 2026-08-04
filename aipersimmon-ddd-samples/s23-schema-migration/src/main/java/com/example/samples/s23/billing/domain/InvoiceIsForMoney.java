package com.example.samples.s23.billing.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** An invoice for nothing is not an invoice. */
record InvoiceIsForMoney(long amountMinor) implements Invariant {

  @Override
  public boolean isBroken() {
    return amountMinor <= 0;
  }

  @Override
  public String message() {
    return "an invoice must be for a positive amount";
  }

  @Override
  public ErrorCode errorCode() {
    return BillingErrorCode.INVOICE_IS_FOR_NOTHING;
  }
}
