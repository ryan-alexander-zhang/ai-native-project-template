package com.aipersimmon.ddd.archunit.fixture.good.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/** A cross-context integration event (a published contract another context emits). */
@EventType(name = "com.example.ordering.StockReserved", version = 1)
public class GoodStockReserved implements IntegrationEvent {

    private final String orderId;

    public GoodStockReserved(String orderId) {
        this.orderId = orderId;
    }

    public String orderId() {
        return orderId;
    }
}
