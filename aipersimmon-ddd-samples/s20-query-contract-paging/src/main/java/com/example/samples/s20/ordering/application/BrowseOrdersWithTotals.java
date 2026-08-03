package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.page.Page;
import jakarta.validation.constraints.NotNull;

/**
 * The same list, with totals. A separate query type rather than a flag on {@link BrowseOrders},
 * because the two return different shapes and cost different amounts: one endpoint whose response
 * body changes with a query parameter is a contract that cannot be typed, documented, or cached.
 */
public record BrowseOrdersWithTotals(@NotNull PageRequest page)
    implements Query<Page<OrderSummary>> {}
