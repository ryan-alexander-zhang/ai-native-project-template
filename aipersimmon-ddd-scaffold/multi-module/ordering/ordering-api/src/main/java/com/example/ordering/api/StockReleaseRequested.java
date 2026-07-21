package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event asking the inventory context to release a reservation — the ordering context's
 * cross-context contract for the stock-release compensation. The saga emits it (carrying the {@code
 * reservationId} inventory handed back on reservation) when a payment decline forces it to undo the
 * held stock before the order can be cancelled.
 */
@EventType(name = "com.example.ordering.StockReleaseRequested", version = 1)
@Externalized("ordering.events")
public record StockReleaseRequested(String orderId, String reservationId)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId();
  }
}
