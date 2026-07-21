package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Domain event: an order was confirmed. Internal to the ordering context. */
public record OrderConfirmedEvent(OrderId orderId) implements DomainEvent {}
