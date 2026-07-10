package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.example.ordering.domain.shared.Money;

/** Domain event: an order was placed. Internal to the ordering context. */
public record OrderPlacedEvent(OrderId orderId, Money total) implements DomainEvent {
}
