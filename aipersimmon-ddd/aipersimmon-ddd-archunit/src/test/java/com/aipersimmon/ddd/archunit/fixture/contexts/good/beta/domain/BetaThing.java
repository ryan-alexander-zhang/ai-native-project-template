package com.aipersimmon.ddd.archunit.fixture.contexts.good.beta.domain;

/** Beta's internal domain type: private to the beta context. */
public class BetaThing {

  private final String value;

  public BetaThing(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
