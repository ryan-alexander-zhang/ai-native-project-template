package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * Violates {@code valueObjectsShouldBeImmutable}: a {@code @ValueObject} with a non-final field, so
 * it can be mutated after construction. Placed in the domain layer so it satisfies {@code
 * domainBuildingBlocksShouldResideInDomain} and fails only the immutability rule.
 */
@ValueObject
public class BadMutableValueObject {

  private String currency;

  public BadMutableValueObject(String currency) {
    this.currency = currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String currency() {
    return currency;
  }
}
