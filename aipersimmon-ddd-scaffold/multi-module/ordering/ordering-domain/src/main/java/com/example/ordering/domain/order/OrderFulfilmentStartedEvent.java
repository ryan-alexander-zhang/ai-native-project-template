package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * Domain event: fulfilment has begun for an order. Emitting it records the persistable fact behind
 * the {@link OrderStatus#FULFILMENT_IN_PROGRESS} boundary — the point past which the customer can
 * no longer self-cancel.
 */
public record OrderFulfilmentStartedEvent(OrderId orderId) implements DomainEvent {}
