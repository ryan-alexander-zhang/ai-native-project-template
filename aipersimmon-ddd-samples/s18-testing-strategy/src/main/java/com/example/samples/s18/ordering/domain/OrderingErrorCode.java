package com.example.samples.s18.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** The ordering context's error codes. */
public enum OrderingErrorCode implements ErrorCode {
  ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND),
  ORDER_HAS_NO_AMOUNT("ordering.order-has-no-amount", ErrorCategory.VALIDATION),
  ORDER_NOT_CONFIRMABLE("ordering.order-not-confirmable", ErrorCategory.CONFLICT);

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
