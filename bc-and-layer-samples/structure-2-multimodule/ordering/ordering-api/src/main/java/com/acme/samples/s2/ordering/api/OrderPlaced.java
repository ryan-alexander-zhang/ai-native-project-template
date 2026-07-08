package com.acme.samples.s2.ordering.api;

import java.util.List;

/** Integration event published by Ordering; consumed by other contexts. */
public record OrderPlaced(String orderId, String customerId, List<Line> lines) {
    public record Line(String sku, int qty, long unitPriceMinor) {}
}
