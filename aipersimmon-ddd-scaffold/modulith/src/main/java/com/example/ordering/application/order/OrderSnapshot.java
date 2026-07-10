package com.example.ordering.application.order;

/** Read-side view of an order returned to callers, decoupled from the aggregate. */
public record OrderSnapshot(String id, String customerId, String status, long totalMinor, String currency) {
}
