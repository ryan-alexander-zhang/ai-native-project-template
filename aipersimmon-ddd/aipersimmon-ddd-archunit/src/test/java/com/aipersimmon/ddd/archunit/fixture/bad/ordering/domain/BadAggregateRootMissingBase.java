package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;

/**
 * Violates {@code aggregateRootsShouldExtendAbstractAggregateRoot}: claims the aggregate root role
 * by annotation but does not extend {@code AbstractAggregateRoot}, so it carries none of the
 * aggregate lifecycle. Placed in the domain layer so it satisfies {@code
 * domainBuildingBlocksShouldResideInDomain} and fails only the base-class rule.
 */
@AggregateRoot
public class BadAggregateRootMissingBase {

  private final String id;

  public BadAggregateRootMissingBase(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }
}
