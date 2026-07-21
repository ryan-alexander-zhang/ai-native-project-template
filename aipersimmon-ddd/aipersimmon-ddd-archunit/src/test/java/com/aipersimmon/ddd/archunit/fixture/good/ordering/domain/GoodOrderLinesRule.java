package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * A well-formed {@link Invariant}: a domain rule, framework-free, plain (not a Spring bean), and
 * raised only through {@code AbstractAggregateRoot.checkInvariant} — never by constructing the
 * violation directly.
 */
public record GoodOrderLinesRule(int lineCount) implements Invariant {

  @Override
  public boolean isBroken() {
    return lineCount <= 0;
  }

  @Override
  public String message() {
    return "an order needs at least one line";
  }

  @Override
  public ErrorCode errorCode() {
    return () -> "ordering.order-empty";
  }
}
