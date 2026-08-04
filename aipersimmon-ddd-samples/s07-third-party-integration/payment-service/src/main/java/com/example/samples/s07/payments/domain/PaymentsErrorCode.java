package com.example.samples.s07.payments.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * This context's error codes, and the categories that decide their HTTP shape.
 *
 * <p>Note there is no code for "the gateway said no". A refusal is not an error of this service — the
 * payment exists, it is in state {@code FAILED}, and a client asking about it gets 200 and that fact.
 * Codes are for requests that could not be carried out, which is why every entry here is about
 * <em>our</em> boundary rather than the provider's answer.
 */
public enum PaymentsErrorCode implements ErrorCode {

  /** A payment for an amount that is not an amount. */
  AMOUNT_NOT_POSITIVE("payments.amount-not-positive", ErrorCategory.DOMAIN_RULE),

  /** No such payment in this service. */
  PAYMENT_NOT_FOUND("payments.payment-not-found", ErrorCategory.NOT_FOUND),

  /**
   * A callback arrived for a payment this service has never heard of. Not a 404 to the gateway — see
   * the controller for why it is answered 200 — but it needs an error code because the ingestion path
   * has to name what it refused to invent.
   */
  UNKNOWN_PAYMENT_REFERENCE("payments.unknown-payment-reference", ErrorCategory.NOT_FOUND);

  private final String code;
  private final ErrorCategory category;

  PaymentsErrorCode(String code, ErrorCategory category) {
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
