package com.example.samples.s19.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * One enum, three kinds of refusal — the codes are what let a client tell them apart even when two of
 * them land on the same status.
 */
public enum OrderingErrorCode implements ErrorCode {
  /** Refused by a precheck: the customer's standing comes from another context. */
  CUSTOMER_BLOCKED("ordering.customer-blocked", ErrorCategory.FORBIDDEN),
  /** Refused by a precheck: the warehouse is not accepting orders right now. */
  WAREHOUSE_CLOSED("ordering.warehouse-closed", ErrorCategory.CONFLICT),
  /** Refused by the aggregate: a rule about the order's own data. */
  QUANTITY_OVER_CAP("ordering.quantity-over-cap", ErrorCategory.DOMAIN_RULE);

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
