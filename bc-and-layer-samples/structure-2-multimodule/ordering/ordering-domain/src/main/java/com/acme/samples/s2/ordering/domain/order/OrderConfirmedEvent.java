package com.acme.samples.s2.ordering.domain.order;

import com.acme.samples.s2.shared.DomainEvent;

/** Domain event: an {@link Order} was confirmed after stock was reserved. */
public record OrderConfirmedEvent(String orderId) implements DomainEvent {
}
