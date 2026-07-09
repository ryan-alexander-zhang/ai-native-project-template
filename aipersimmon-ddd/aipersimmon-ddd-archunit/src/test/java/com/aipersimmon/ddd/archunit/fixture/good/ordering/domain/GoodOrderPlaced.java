package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** A domain event correctly placed in the domain layer. */
public class GoodOrderPlaced implements DomainEvent {

    private final String orderId;

    public GoodOrderPlaced(String orderId) {
        this.orderId = orderId;
    }

    public String orderId() {
        return orderId;
    }
}
