package com.acme.samples.s2.ordering.application.order;

/**
 * Read-side DTO for an order (CQRS-lite). Returned by the read model; exposes no
 * domain types so adapters never touch the aggregate.
 */
public record OrderSnapshot(String orderId, String status, long totalMinor, String currency) {
}
