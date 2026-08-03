package com.example.samples.s11.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** Stable identities for this context's refusals. */
public enum OrderingErrorCode implements ErrorCode {
  /** The order does not exist — or no longer does. */
  ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND),
  /** Refused by the transition table: only an open order can be paid. */
  ORDER_NOT_PAYABLE("ordering.order-not-payable", ErrorCategory.CONFLICT),
  /** Refused by the transition table: only an open order can be closed. */
  ORDER_NOT_CLOSABLE("ordering.order-not-closable", ErrorCategory.CONFLICT);

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
