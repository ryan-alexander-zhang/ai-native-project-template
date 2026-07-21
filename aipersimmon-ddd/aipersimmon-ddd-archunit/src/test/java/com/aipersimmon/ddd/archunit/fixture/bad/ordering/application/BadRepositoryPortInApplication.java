package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.core.annotation.Repository;

/**
 * Violates {@code portsShouldBeInterfacesInDomain}: a core {@code @Repository} port declared in the
 * application layer instead of the domain.
 */
@Repository
public interface BadRepositoryPortInApplication {

  void save(String item);
}
