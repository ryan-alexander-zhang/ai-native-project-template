package com.example.samples.s04.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** Stable identities for this context's refusals. */
public enum OrderingErrorCode implements ErrorCode {
  ORDER_HAS_NO_LINES("ordering.order-has-no-lines", ErrorCategory.DOMAIN_RULE);

  private final String code;
  private final ErrorCategory category;

  OrderingErrorCode(String code, ErrorCategory category) {
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
