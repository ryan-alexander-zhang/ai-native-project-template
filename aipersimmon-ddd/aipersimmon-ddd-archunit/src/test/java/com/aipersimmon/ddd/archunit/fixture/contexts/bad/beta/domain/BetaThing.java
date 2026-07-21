package com.aipersimmon.ddd.archunit.fixture.contexts.bad.beta.domain;

/** Beta's internal domain type: private to the beta context, not part of its contract. */
public class BetaThing {

  private final String value;

  public BetaThing(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
