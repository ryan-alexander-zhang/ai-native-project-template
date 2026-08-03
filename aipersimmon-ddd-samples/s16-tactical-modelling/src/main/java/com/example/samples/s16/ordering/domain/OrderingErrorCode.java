package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The ordering context's error codes: one enum per context, so the catalogue lives in one place. The
 * ArchUnit rule {@code errorCodesShouldBeEnums} keeps it that way.
 */
public enum OrderingErrorCode implements ErrorCode {
  ORDER_HAS_NO_LINES("ordering.order-has-no-lines", ErrorCategory.VALIDATION),
  ORDER_LINES_DUPLICATE_SKU("ordering.order-lines-duplicate-sku", ErrorCategory.VALIDATION),
  ORDER_TOTAL_EXCEEDS_CEILING("ordering.order-total-exceeds-ceiling", ErrorCategory.DOMAIN_RULE),
  ORDER_LINES_FROZEN("ordering.order-lines-frozen", ErrorCategory.CONFLICT),
  ORDER_LINE_NOT_FOUND("ordering.order-line-not-found", ErrorCategory.NOT_FOUND),
  ORDER_NOT_PLACEABLE("ordering.order-not-placeable", ErrorCategory.CONFLICT),
  ORDER_NOT_PAYABLE("ordering.order-not-payable", ErrorCategory.CONFLICT),
  ORDER_NOT_CANCELLABLE("ordering.order-not-cancellable", ErrorCategory.CONFLICT);

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
