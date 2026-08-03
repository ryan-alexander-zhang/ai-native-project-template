package com.example.samples.s20.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The codes this context can raise. Four of the five are about a <em>read</em> — which is worth
 * noticing: a query contract fails in as many ways as a command contract, and a read-side failure
 * that arrives as a bare 500 is a contract that was never written down.
 */
public enum OrderingErrorCode implements ErrorCode {
  /** The order does not exist. */
  ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND),
  /** Refused by the aggregate. */
  QUANTITY_NOT_POSITIVE("ordering.quantity-not-positive", ErrorCategory.DOMAIN_RULE),
  /** The cursor did not decode: truncated, re-encoded, or hand-written. */
  MALFORMED_CURSOR("ordering.malformed-cursor", ErrorCategory.VALIDATION),
  /** The cursor decoded, but it belongs to a different question than the one being asked. */
  CURSOR_DOES_NOT_MATCH_QUERY("ordering.cursor-does-not-match-query", ErrorCategory.VALIDATION),
  /** The requested page size is outside the range this endpoint serves. */
  PAGE_SIZE_OUT_OF_RANGE("ordering.page-size-out-of-range", ErrorCategory.VALIDATION);

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
