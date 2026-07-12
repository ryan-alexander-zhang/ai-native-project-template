package com.aipersimmon.ddd.archunit.fixture.good.ordering.adapter;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.application.GoodPlaceOrderService;

/**
 * Well-behaved inbound adapter: it drives a use case through the application layer
 * and never reaches into the domain, satisfying the stricter
 * {@code adapterShouldNotDependOnDomain} rule.
 */
public class GoodOrderEndpoint {

    private final GoodPlaceOrderService placeOrder;

    public GoodOrderEndpoint(GoodPlaceOrderService placeOrder) {
        this.placeOrder = placeOrder;
    }

    public String place(String customerId) {
        return placeOrder.place(customerId);
    }
}
