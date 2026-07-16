package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Domain event: a confirmed order was dispatched. After this, only the return flow applies. */
public record OrderShippedEvent(OrderId orderId) implements DomainEvent {
}
