package com.example.contracts;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * Integration event published when an order is placed. A shared cross-service
 * contract: both services depend on this module, so the type is identical on the
 * producing (ordering) and consuming (inventory) side and travels over Kafka by
 * its class name. It carries the ids and quantities another service needs, never
 * an internal domain model.
 */
public record OrderPlaced(String orderId, List<Line> lines) implements IntegrationEvent {

    public record Line(String sku, int quantity) {
    }
}
