package com.aipersimmon.ddd.archunit.fixture.bad.ordering.api;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * A published value object with a reassignable field. This is the check the {@code ..api..}
 * allowance exists to restore: before it, a project adopting the cross-context rules had to strip
 * {@code @ValueObject} from its published types to get a green build, and stripping it took {@code
 * valueObjectsShouldBeImmutable} with it — so the types most exposed were the ones no longer
 * checked. Keeping the marker legal in {@code ..api..} is what makes this class reportable.
 */
@ValueObject
public class BadMutablePublishedValueObject {

  private String currency;

  public BadMutablePublishedValueObject(String currency) {
    this.currency = currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }
}
