package com.example.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when a prior stock reservation has been released — the inventory
 * context's cross-context contract for a completed compensation. It is the fact the ordering
 * process manager waits for before it cancels an order for a payment decline: only once this event
 * names the order and its {@code reservationId} does the process manager hold the stock-release
 * evidence the ordering domain demands.
 */
@EventType(name = "com.example.inventory.StockReleased", version = 1, source = "/inventory")
@Externalized("inventory.events")
public record StockReleased(String orderId, String reservationId) implements IntegrationEvent {

  public StockReleased {
    // Both ids are the whole message: this event is the stock-release evidence the ordering
    // domain demands, and evidence that names nothing proves nothing (issue-00143).
    Contract.required(orderId, "orderId");
    Contract.required(reservationId, "reservationId");
  }

  @Override
  public String subject() {
    return orderId();
  }
}
