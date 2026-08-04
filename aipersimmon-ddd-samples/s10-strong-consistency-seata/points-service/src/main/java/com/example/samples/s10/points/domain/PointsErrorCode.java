package com.example.samples.s10.points.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * This context's refusals — and there are few, because almost everything a participant can encounter is an
 * outcome the caller has to act on rather than an error. Only "confirm a reservation that is not here" is a
 * genuine fault, and it is the one that throws.
 */
public enum PointsErrorCode implements ErrorCode {
  POINTS_ACCOUNT_NOT_FOUND("points.account-not-found", ErrorCategory.NOT_FOUND),

  /** Confirm arrived for a reservation that is not here. Never legitimate; never guessed at. */
  NOTHING_TO_CONFIRM("points.nothing-to-confirm", ErrorCategory.DOMAIN_RULE);

  private final String code;
  private final ErrorCategory category;

  PointsErrorCode(String code, ErrorCategory category) {
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
