package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A well-formed aggregate root: annotated {@code @AggregateRoot}, placed in the domain
 * layer, and extending {@link AbstractAggregateRoot} so it actually carries the
 * aggregate lifecycle. Exercises the good path of
 * {@code domainBuildingBlocksShouldResideInDomain} and
 * {@code aggregateRootsShouldExtendAbstractAggregateRoot}.
 */
@AggregateRoot
public class GoodStockItem extends AbstractAggregateRoot<String> {

    private final String sku;

    public GoodStockItem(String sku) {
        this.sku = sku;
    }

    @Override
    public String id() {
        return sku;
    }
}
