package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A well-formed aggregate root: annotated {@code @AggregateRoot}, placed in the domain layer, and
 * extending {@link AbstractAggregateRoot} so it actually carries the aggregate lifecycle. Exercises
 * the good path of {@code domainBuildingBlocksShouldResideInDomain} and {@code
 * aggregateRootsShouldExtendAbstractAggregateRoot}.
 */
@AggregateRoot
public class GoodStockItem extends AbstractAggregateRoot<GoodSku> {

  private final GoodSku sku;

  public GoodStockItem(GoodSku sku) {
    this.sku = sku;
  }

  @Override
  public GoodSku id() {
    return sku;
  }
}
