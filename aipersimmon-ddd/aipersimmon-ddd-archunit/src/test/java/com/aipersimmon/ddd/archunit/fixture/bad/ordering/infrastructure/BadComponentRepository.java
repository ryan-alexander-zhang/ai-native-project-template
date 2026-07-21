package com.aipersimmon.ddd.archunit.fixture.bad.ordering.infrastructure;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain.BadItems;
import org.springframework.stereotype.Component;

/**
 * Violates {@code implementationsShouldBeSpringRepositories}: a repository implementation placed
 * correctly in infrastructure but annotated with a bare {@code @Component} rather than Spring's
 * {@code @Repository}, so it forgoes the precise stereotype and persistence-exception translation.
 * Correctly placed, so it does not trip {@code implementationsShouldResideInInfrastructure}.
 */
@Component
public class BadComponentRepository implements BadItems {

  @Override
  public void save(String item) {
    // no-op
  }
}
