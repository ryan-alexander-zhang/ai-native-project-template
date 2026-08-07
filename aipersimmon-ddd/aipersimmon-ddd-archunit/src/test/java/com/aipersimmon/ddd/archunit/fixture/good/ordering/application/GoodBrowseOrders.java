package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.List;

/**
 * A query that answers with read models rather than with the write model, wrapped in a collection
 * so the good path of {@code queryResultsShouldNotBeAggregatesOrEntities} runs through the same
 * generic-signature unwrapping the violating fixture does — a rule that only read the raw result
 * type would pass this for the wrong reason.
 */
public record GoodBrowseOrders(String customerId) implements Query<List<GoodOrderView>> {}
