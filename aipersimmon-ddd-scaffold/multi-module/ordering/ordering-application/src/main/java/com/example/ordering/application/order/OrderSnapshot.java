package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** Read-side view of an order returned to callers, decoupled from the aggregate. */
@ReadModel
public record OrderSnapshot(String id, String customerId, String status, long totalMinor, String currency) {
}
