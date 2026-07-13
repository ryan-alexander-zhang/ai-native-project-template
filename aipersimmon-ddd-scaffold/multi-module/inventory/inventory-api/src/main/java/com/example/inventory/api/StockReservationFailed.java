package com.example.inventory.api;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order could not be reserved — the
 * inventory context's cross-context contract for a failed reservation. It carries the
 * order id, a stable machine-readable {@code code} (the failing domain
 * {@link com.aipersimmon.ddd.core.error.ErrorCode}'s value, e.g.
 * {@code "inventory.insufficient-stock"}), and a human-readable {@code reason}, so the
 * originating context can branch on the code and compensate (here, cancel the order).
 * Reporting failure as an event, rather than throwing, is what lets the order-fulfilment
 * saga react to it as one of the flow's outcomes — and carrying the code on the event is
 * how a bounded context with no HTTP surface still surfaces a stable error identity.
 */
public record StockReservationFailed(String orderId, String code, String reason) implements IntegrationEvent {
}
