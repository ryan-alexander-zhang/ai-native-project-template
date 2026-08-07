package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItem;
import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/**
 * Violates {@code queryResultsShouldNotBeAggregatesOrEntities}: the query declares the aggregate
 * root as its answer, publishing the write model as the read contract.
 *
 * <p>Wrapped in an {@code Optional} on purpose — the aggregate is not the result type itself but a
 * generic argument of it, which is how this reaches a codebase in practice ({@code
 * Optional<Order>}, {@code List<Order>}, {@code Slice<Order>}) and what a rule reading only the raw
 * result type would miss.
 */
public record BadFindOrderAggregate(String orderId) implements Query<Optional<GoodStockItem>> {}
