package com.example.inventory.api;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order could not be reserved — the
 * inventory context's cross-context contract for a failed reservation. It carries
 * the order id and a reason so the originating context can compensate (here, cancel
 * the order). Reporting failure as an event, rather than throwing, is what lets the
 * order-fulfilment saga react to it as one of the flow's outcomes.
 */
public record StockReservationFailed(String orderId, String reason) implements IntegrationEvent {
}
