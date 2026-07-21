package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.core.annotation.Service;

/**
 * Violates {@code domainServicesShouldResideInDomain}: a core {@code @Service} (domain service)
 * declared in the application layer instead of the domain.
 */
@Service
public class BadDomainServiceInApplication {

  public void run() {
    // no-op
  }
}
