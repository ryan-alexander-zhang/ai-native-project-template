package com.acme.samples.s1.shared;

import java.util.List;

/**
 * Integration event: an order was placed in the Ordering context. Part of the
 * published language consumed by other contexts (Inventory). Carried over Kafka.
 */
public record OrderPlaced(String orderId, String customerId, List<Line> lines) {
    public record Line(String sku, int qty, long unitPriceMinor) {}
}
