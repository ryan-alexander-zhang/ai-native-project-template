package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * Domain event: an order cleared review and is now eligible for fulfilment. This — not
 * {@link OrderPlacedEvent} — is what should trigger stock reservation once a review step
 * exists; {@code OrderPlacedEvent} means only that the order was created.
 */
public record OrderReadyForFulfilmentEvent(OrderId orderId) implements DomainEvent {
}
