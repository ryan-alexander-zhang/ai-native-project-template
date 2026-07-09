package com.acme.samples.s2.ordering.api;

/**
 * Integration event: an order was cancelled (rejected, or a stale PENDING order
 * compensated on timeout — analysis-00005 §八/G6). Consumed by Inventory to
 * release any stock it had reserved for the order. Thin: id only.
 */
public record OrderCancelled(String orderId) implements IntegrationEvent {
}
