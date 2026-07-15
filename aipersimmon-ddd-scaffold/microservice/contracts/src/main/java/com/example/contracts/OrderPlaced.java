package com.example.contracts;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * Integration event published when an order is placed. A shared cross-service
 * contract: both services depend on this module, so the type is identical on the
 * producing (ordering) and consuming (inventory) side and travels over Kafka by its
 * declared logical {@link EventType} (not its Java class name). It carries the ids
 * and quantities another service needs, never an internal domain model.
 */
@EventType(name = "com.example.ordering.OrderPlaced", version = 1)
public record OrderPlaced(String orderId, List<Line> lines) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }

    public record Line(String sku, int quantity) {
    }
}
