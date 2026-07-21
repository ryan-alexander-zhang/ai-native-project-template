package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * A well-formed error catalogue: an enum implementing {@link ErrorCode}, so the context's error
 * codes live in one enumerated place. Exercises the good path of {@code errorCodesShouldBeEnums}.
 */
public enum GoodOrderingErrorCode implements ErrorCode {
  ORDER_EMPTY("ordering.order-empty");

  private final String code;

  GoodOrderingErrorCode(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
