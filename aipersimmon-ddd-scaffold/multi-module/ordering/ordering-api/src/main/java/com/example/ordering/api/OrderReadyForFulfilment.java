package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;

/**
 * Integration event published when an order is cleared for fulfilment — the ordering context's
 * cross-context contract that asks inventory to reserve stock. It is announced when the order
 * becomes ready (immediately at placement for an order needing no review, or later when review is
 * approved), <em>not</em> merely when the order is created: an order held for manual review
 * reserves nothing until it clears. It carries the ids and quantities inventory needs, never the
 * internal domain model.
 */
@EventType(name = "com.example.ordering.OrderReadyForFulfilment", version = 1)
@Externalized("ordering.events")
public record OrderReadyForFulfilment(String orderId, List<Line> lines)
    implements IntegrationEvent {

  public OrderReadyForFulfilment {
    // Defensive copy so this published event stays immutable and cannot be mutated
    // through the caller's list reference after construction.
    lines = lines == null ? null : List.copyOf(lines);
  }

  @Override
  public String subject() {
    return orderId();
  }

  public record Line(String sku, int quantity) {}
}
