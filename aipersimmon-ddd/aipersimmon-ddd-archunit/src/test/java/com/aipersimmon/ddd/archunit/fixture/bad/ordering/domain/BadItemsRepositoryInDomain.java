package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

/**
 * Violates {@code implementationsShouldResideInInfrastructure}: a concrete implementation of the
 * {@link BadItems} repository port placed in the domain layer instead of infrastructure.
 */
public class BadItemsRepositoryInDomain implements BadItems {

  @Override
  public void save(String item) {
    // no-op
  }
}
