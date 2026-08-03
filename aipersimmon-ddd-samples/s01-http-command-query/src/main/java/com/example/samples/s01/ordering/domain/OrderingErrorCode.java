package com.example.samples.s01.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The ordering context's error codes. One enum per bounded context, prefixed with the context name:
 * once published, a code is part of the outward contract.
 *
 * <p>The category alone is enough to get a real problem type and status out of
 * {@code DefaultProblemFamilies} — a {@code ProblemCatalog} entry is only needed for the few codes
 * that deserve their own problem type.
 */
public enum OrderingErrorCode implements ErrorCode {
  ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND),
  ORDER_HAS_NO_LINES("ordering.order-has-no-lines", ErrorCategory.VALIDATION),
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
