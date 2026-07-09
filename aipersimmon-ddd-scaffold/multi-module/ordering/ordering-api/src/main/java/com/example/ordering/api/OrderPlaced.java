package com.example.ordering.api;

import java.util.List;

/**
 * Integration event published when an order is placed — the ordering context's
 * cross-context contract. It carries the ids and quantities another context needs
 * (here, so inventory can reserve stock), never the internal domain model.
 */
public record OrderPlaced(String orderId, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
