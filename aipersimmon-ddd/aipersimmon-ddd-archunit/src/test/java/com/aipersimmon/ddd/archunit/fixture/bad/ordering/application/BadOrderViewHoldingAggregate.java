package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItem;
import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * Violates {@code readModelsShouldNotHoldAggregatesOrEntities}: a read model with the aggregate
 * root inside it, so answering the query means rebuilding the write model row by row.
 *
 * <p>Correctly placed in the application layer, so it passes {@code
 * readModelsShouldResideInApplicationOrApi} and fails only the other half of {@code
 * readModelsShouldBeProjectionShapes}.
 */
@ReadModel
public record BadOrderViewHoldingAggregate(String orderId, GoodStockItem item) {}
