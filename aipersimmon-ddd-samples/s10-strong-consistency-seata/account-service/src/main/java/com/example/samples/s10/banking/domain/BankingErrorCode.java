package com.example.samples.s10.banking.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** This context's refusals. */
public enum BankingErrorCode implements ErrorCode {
  ACCOUNT_NOT_FOUND("banking.account-not-found", ErrorCategory.NOT_FOUND),

  INSUFFICIENT_FUNDS("banking.insufficient-funds", ErrorCategory.DOMAIN_RULE),

  /**
   * The points participant refused. A business refusal from the other side of the global transaction, and
   * the reason it must roll back rather than be logged.
   */
  POINTS_REFUSED("banking.points-refused", ErrorCategory.DOMAIN_RULE);

  private final String code;
  private final ErrorCategory category;

  BankingErrorCode(String code, ErrorCategory category) {
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
