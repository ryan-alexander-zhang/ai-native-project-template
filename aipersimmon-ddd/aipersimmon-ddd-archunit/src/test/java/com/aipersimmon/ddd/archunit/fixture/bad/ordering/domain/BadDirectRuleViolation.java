package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import com.aipersimmon.ddd.core.rule.InvariantViolationException;

/**
 * Violates {@code invariantViolationsShouldOnlyComeFromCheckInvariant}: it raises an {@link
 * InvariantViolationException} by constructing it directly, instead of going through {@code
 * AbstractAggregateRoot.checkInvariant}.
 */
public class BadDirectRuleViolation implements Invariant {

  @Override
  public boolean isBroken() {
    return true;
  }

  @Override
  public String message() {
    return "always broken";
  }

  @Override
  public ErrorCode errorCode() {
    return () -> "fixture.always-broken";
  }

  public void enforce() {
    throw new InvariantViolationException(this);
  }
}
