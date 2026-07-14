package com.example.ordering.api;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * Integration event published when an order is placed — the ordering context's
 * cross-context contract. It carries the ids and quantities another context needs
 * (here, so inventory can reserve stock), never the internal domain model.
 */
public record OrderPlaced(String orderId, List<Line> lines) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }

    public record Line(String sku, int quantity) {
    }
}
