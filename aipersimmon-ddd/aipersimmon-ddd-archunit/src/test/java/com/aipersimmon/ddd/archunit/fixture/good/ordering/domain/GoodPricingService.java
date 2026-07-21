package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Service;

/**
 * A well-formed domain service: annotated with the core {@code @Service} and placed in the domain
 * layer, holding stateless behaviour over domain objects. Exercises the good path of {@code
 * domainServicesShouldResideInDomain}.
 */
@Service
public class GoodPricingService {

  public long total(GoodMoney line, int quantity) {
    return line.amountMinor() * quantity;
  }
}
