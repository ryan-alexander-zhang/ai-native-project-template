package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.List;

/**
 * Query for the current availability of a set of SKUs. Answered from the stock repository without
 * changing state, and dispatched through the query bus by the inbound adapter that fronts the
 * {@code StockAvailabilityApi} Open Host Service. Its SKUs and result stay in the application's own
 * terms — the mapping to and from the published contract lives in the adapter, not here.
 */
public record CheckStockAvailability(List<String> skus) implements Query<List<StockLevel>> {}
