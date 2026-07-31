package com.example.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order could not be reserved — the inventory
 * context's cross-context contract for a failed reservation. It carries the order id, a stable
 * machine-readable {@code code} (the failing domain {@link
 * com.aipersimmon.ddd.core.error.ErrorCode}'s value, e.g. {@code "inventory.insufficient-stock"}),
 * and a human-readable {@code reason}, so the originating context can branch on the code and
 * compensate (here, cancel the order).
 *
 * <p>{@code code} is <strong>never null</strong>: that is a contract guarantee, not a convention. A
 * domain refusal carrying no code of its own leaves inventory as {@code inventory.unspecified}
 * rather than as {@code null} — consumers are entitled to reject a codeless failure outright
 * (ordering's evidence types do), so producing one would poison their consuming transaction
 * (issue-00131). Reporting failure as an event, rather than throwing, is what lets the
 * order-fulfilment process manager react to it as one of the flow's outcomes — and carrying the
 * code on the event is how a bounded context with no HTTP surface still surfaces a stable error
 * identity.
 */
@EventType(name = "com.example.inventory.StockReservationFailed", version = 1)
@Externalized("inventory.events")
public record StockReservationFailed(String orderId, String code, String reason)
    implements IntegrationEvent {

  @Override
  public String subject() {
    return orderId();
  }
}
