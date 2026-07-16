package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * Domain event: an order was cancelled. It carries the published {@link CancellationCategory}
 * only — never the internal evidence refs. The saga waits for this event before it treats a
 * cancellation as done, rather than assuming success the moment it sends the command.
 */
public record OrderCancelledEvent(OrderId orderId, CancellationCategory category) implements DomainEvent {
}
