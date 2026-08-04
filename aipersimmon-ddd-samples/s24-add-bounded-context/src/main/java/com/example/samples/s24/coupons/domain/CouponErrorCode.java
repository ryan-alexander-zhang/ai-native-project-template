package com.example.samples.s24.coupons.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * This context's refusals.
 *
 * <p>Worth noticing which refusals are <em>not</em> here: an expired coupon and an exhausted one. Those are not
 * errors, they are answers — a {@code CouponQuote} carrying {@code accepted = false} and a reason. Modelling them as
 * exceptions would make the ordinary case of a customer typing an old code into an exceptional path, and would force
 * ordering to catch this context's exception types, which is a far deeper coupling than reading a boolean.
 *
 * <p>Error codes stay out of {@code api} for the same reason: nothing outside this context branches on one. The day
 * something does, the code that it branches on moves to {@code api} and becomes a promise — which is a decision worth
 * taking on purpose rather than by having put them all there in advance.
 */
public enum CouponErrorCode implements ErrorCode {
  COUPON_NOT_FOUND("coupons.not-found", ErrorCategory.NOT_FOUND),
  COUPON_ALREADY_ISSUED("coupons.already-issued", ErrorCategory.CONFLICT);

  private final String code;
  private final ErrorCategory category;

  CouponErrorCode(String code, ErrorCategory category) {
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
