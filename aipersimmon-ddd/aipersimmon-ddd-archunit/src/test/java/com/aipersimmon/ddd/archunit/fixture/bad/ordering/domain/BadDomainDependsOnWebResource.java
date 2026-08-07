package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.interfaces.BadOrderResource;

/**
 * Violates {@code domainShouldNotDependOnOuterLayers} through the {@code ..interfaces..} spelling
 * of the interface layer.
 *
 * <p>This fixture exists to measure the widening rather than the rule: until the layering rules
 * read {@link com.aipersimmon.ddd.archunit.Layers}, they matched {@code ..adapter..} only, so a
 * domain class reaching into a web resource under {@code ..interfaces..} was reported by nothing at
 * all.
 */
public class BadDomainDependsOnWebResource {

  private final BadOrderResource resource;

  public BadDomainDependsOnWebResource(BadOrderResource resource) {
    this.resource = resource;
  }

  public BadOrderResource resource() {
    return resource;
  }
}
