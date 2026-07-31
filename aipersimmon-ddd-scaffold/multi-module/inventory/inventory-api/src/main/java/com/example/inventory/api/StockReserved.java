package com.example.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order has been reserved — the inventory context's
 * cross-context contract. It carries the order id so the originating context can react, plus the
 * {@code reservationId} handle the reacting process manager must keep in order to release the very
 * same reservation later (idempotently) should the flow have to compensate.
 */
@EventType(name = "com.example.inventory.StockReserved", version = 1, source = "/inventory")
@Externalized("inventory.events")
public record StockReserved(String orderId, String reservationId) implements IntegrationEvent {

  public StockReserved {
    // Both ids are the whole message: the reservationId is the very handle the compensation
    // needs later, so an event without it is unusable (issue-00143).
    Contract.required(orderId, "orderId");
    Contract.required(reservationId, "reservationId");
  }

  @Override
  public String subject() {
    return orderId();
  }
}
