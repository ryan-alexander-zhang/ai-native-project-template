package com.example.samples.s23.billing.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** Stable identities for this context's refusals. */
public enum BillingErrorCode implements ErrorCode {
  INVOICE_IS_FOR_NOTHING("billing.invoice-is-for-nothing", ErrorCategory.DOMAIN_RULE);

  private final String code;
  private final ErrorCategory category;

  BillingErrorCode(String code, ErrorCategory category) {
    this.code = code;
    this.category = category;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public ErrorCategory category() {
    return category;
  }
}
