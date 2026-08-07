package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.interfaces.BadOrderResource;

/**
 * Violates {@code applicationShouldNotDependOnInfrastructureOrInterface} through the {@code
 * ..interfaces..} spelling — the case the rule's own name always claimed to cover and, until the
 * layering rules read {@link com.aipersimmon.ddd.archunit.Layers}, did not.
 */
public class BadApplicationDependsOnWebResource {

  private final BadOrderResource resource;

  public BadApplicationDependsOnWebResource(BadOrderResource resource) {
    this.resource = resource;
  }

  public BadOrderResource resource() {
    return resource;
  }
}
