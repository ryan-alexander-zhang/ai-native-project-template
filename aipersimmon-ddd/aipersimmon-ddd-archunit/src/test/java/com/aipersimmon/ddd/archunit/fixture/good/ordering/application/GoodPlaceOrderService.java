package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodOrder;

/** Well-behaved application class: depends inward on the domain only. */
public class GoodPlaceOrderService {

    public String idOf(GoodOrder order) {
        return order.id();
    }
}
