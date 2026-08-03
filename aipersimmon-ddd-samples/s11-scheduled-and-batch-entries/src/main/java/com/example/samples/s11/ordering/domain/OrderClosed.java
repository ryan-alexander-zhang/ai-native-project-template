package com.example.samples.s11.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * What a closed order tells the rest of the system.
 *
 * <p>The event is half the reason the sweep goes through the aggregate: a bulk {@code UPDATE} closes
 * the rows and tells nobody, so every reaction that should follow a closure — releasing the reserved
 * stock, notifying the customer — silently does not happen for the orders the batch touched.
 */
public record OrderClosed(OrderId orderId, String customerId) implements DomainEvent {}
