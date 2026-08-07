package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.cqrs.CommandContext;

/**
 * Violates {@code domainShouldDependOnTheFrameworkCoreOnly}: a domain class reaching for a
 * framework module other than core — here the CQRS dispatch context, which tells the model it is
 * being dispatched.
 *
 * <p>It passes {@code domainShouldBeFrameworkFree} (nothing here is Spring, JPA or Jackson), which
 * is the point of the fixture: the older rule cannot see this, and that gap is what the new one
 * closes.
 */
public class BadDomainDependsOnCqrs {

  private final CommandContext context;

  public BadDomainDependsOnCqrs(CommandContext context) {
    this.context = context;
  }

  public CommandContext context() {
    return context;
  }
}
