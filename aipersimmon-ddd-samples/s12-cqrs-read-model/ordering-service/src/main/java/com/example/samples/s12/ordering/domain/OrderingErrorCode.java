package com.example.samples.s12.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** This context's refusals. */
public enum OrderingErrorCode implements ErrorCode {
  ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND);

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
