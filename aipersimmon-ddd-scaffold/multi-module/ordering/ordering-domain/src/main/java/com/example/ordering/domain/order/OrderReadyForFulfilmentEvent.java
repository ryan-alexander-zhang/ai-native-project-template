package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * Domain event: an order is cleared for fulfilment — either it needed no manual review, or its
 * review was approved. This — not {@link OrderPlacedEvent} — is what triggers stock reservation and
 * starts the fulfilment process; {@code OrderPlacedEvent} means only that the order was created and
 * may still be awaiting review.
 */
public record OrderReadyForFulfilmentEvent(OrderId orderId) implements DomainEvent {}
