package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * Violates {@code errorCodesShouldBeEnums}: an {@link ErrorCode} modelled as a plain class rather
 * than an enum, so the context's error codes are not gathered into a single enumerated catalogue.
 */
public class BadErrorCodeClass implements ErrorCode {

  @Override
  public String code() {
    return "ordering.bad-error-code";
  }
}
