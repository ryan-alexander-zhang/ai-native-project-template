package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * Violates {@code invariantsShouldResideInDomain}: an {@link Invariant} declared in the application
 * layer instead of the domain.
 */
public record BadRuleInApplication(int value) implements Invariant {

  @Override
  public boolean isBroken() {
    return value < 0;
  }

  @Override
  public String message() {
    return "value must be >= 0";
  }

  @Override
  public ErrorCode errorCode() {
    return () -> "fixture.value-negative";
  }
}
