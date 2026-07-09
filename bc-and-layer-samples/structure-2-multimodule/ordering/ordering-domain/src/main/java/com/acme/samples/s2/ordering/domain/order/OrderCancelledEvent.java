package com.acme.samples.s2.ordering.domain.order;

import com.acme.samples.s2.shared.DomainEvent;

/** Domain event: an {@link Order} was cancelled (e.g. stock could not be reserved). */
public record OrderCancelledEvent(String orderId, String reason) implements DomainEvent {
}
