package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Entity;

/**
 * A well-formed entity: annotated {@code @Entity} and placed in the domain layer. Exercises the
 * {@code @Entity} arm of {@code domainBuildingBlocksShouldResideInDomain}.
 */
@Entity
public class GoodStockLot {

  private final String lotId;

  public GoodStockLot(String lotId) {
    this.lotId = lotId;
  }

  public String lotId() {
    return lotId;
  }
}
