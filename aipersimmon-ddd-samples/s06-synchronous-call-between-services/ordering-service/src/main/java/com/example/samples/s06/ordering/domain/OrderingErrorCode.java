package com.example.samples.s06.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * Stable identities for this context's refusals — including the two that describe somebody else's
 * behaviour in <em>this</em> context's words.
 *
 * <p>{@link #RISK_REJECTED} is a business outcome: the request was fine and the answer was no.
 * {@link #RISK_UNAVAILABLE} is not an outcome at all, it is an absence of one — and the two must not
 * share a code, because a client should retry the second and never the first. The remote service's own
 * status codes and problem types do not appear anywhere in this enum: translating them into these two is
 * the whole job of the adapter.
 */
public enum OrderingErrorCode implements ErrorCode {
  NON_POSITIVE_AMOUNT("ordering.non-positive-amount", ErrorCategory.DOMAIN_RULE),
  RISK_REJECTED("ordering.risk-rejected", ErrorCategory.DOMAIN_RULE),
  /**
   * Categorised {@code UNEXPECTED} because the category enum has no "a dependency is down" member — and
   * that default (500) is wrong for a caller, who should be told to try again. The interfaces layer
   * overrides it to 503 through a {@code ProblemCatalog}; see {@code RiskProblemCatalog}.
   */
  RISK_UNAVAILABLE("ordering.risk-unavailable", ErrorCategory.UNEXPECTED);

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
