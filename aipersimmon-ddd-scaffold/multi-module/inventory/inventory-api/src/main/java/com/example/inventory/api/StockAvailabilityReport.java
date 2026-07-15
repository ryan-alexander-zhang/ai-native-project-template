package com.example.inventory.api;

import java.util.List;

/**
 * Response DTO of {@link StockAvailabilityApi}: for each requested SKU, whether
 * inventory can currently offer it. A published-language type — it exposes a boolean
 * verdict per SKU, not inventory's internal stock levels or aggregates, so the exact
 * quantity on hand stays an inventory secret.
 */
public record StockAvailabilityReport(List<Item> items) {

    /** The verdict for one SKU: {@code available} is true when inventory can offer it now. */
    public record Item(String sku, boolean available) {
    }
}
