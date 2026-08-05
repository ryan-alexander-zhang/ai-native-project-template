package com.example.samples.s25.refunds.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The refusals — and every one of them is a rule the monolith had, expressed somewhere it can be found.
 *
 * <p>That is the actual deliverable of a first extraction. Not a cleaner class diagram: a list of refusals that
 * used to be an {@code if} next to some SQL, a {@code WHERE} clause, or nothing at all.
 */
public enum RefundErrorCode implements ErrorCode {
  REFUND_NOT_FOUND("refunds.not-found", ErrorCategory.NOT_FOUND),
  ORDER_NOT_FOUND("refunds.order-not-found", ErrorCategory.NOT_FOUND),

  /** Was an {@code if} in {@code LegacyOrderService.raiseRefund}. */
  ORDER_IS_CANCELLED("refunds.order-is-cancelled", ErrorCategory.DOMAIN_RULE),

  /** Was a comparison against a value read with no lock. */
  EXCEEDS_ORDER_TOTAL("refunds.exceeds-order-total", ErrorCategory.DOMAIN_RULE),

  /** Was <strong>nothing at all</strong>: the monolith never checked it. */
  ALREADY_OPEN("refunds.already-open", ErrorCategory.CONFLICT),

  /** Was a {@code WHERE state = 'OPEN'} that turned a second approval into a silent no-op. */
  ALREADY_CLOSED("refunds.already-closed", ErrorCategory.CONFLICT);

  private final String code;
  private final ErrorCategory category;

  RefundErrorCode(String code, ErrorCategory category) {
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
