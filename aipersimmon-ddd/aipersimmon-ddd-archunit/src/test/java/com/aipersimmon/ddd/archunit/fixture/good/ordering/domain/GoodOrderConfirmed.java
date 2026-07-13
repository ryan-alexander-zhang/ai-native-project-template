package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.DomainEvent;

/**
 * A domain event declared via the {@code @DomainEvent} annotation rather than the
 * marker interface, correctly placed in the domain layer. Exercises the annotation
 * path of the domain-event containment rule.
 */
@DomainEvent
public class GoodOrderConfirmed {

    private final String orderId;

    public GoodOrderConfirmed(String orderId) {
        this.orderId = orderId;
    }

    public String orderId() {
        return orderId;
    }
}
