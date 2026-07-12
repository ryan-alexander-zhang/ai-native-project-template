package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodOrder;

/** Well-behaved application class: depends inward on the domain only. */
public class GoodPlaceOrderService {

    public String idOf(GoodOrder order) {
        return order.id();
    }

    /** A use-case entry point an adapter can call without touching domain types. */
    public String place(String customerId) {
        return customerId;
    }
}
