package com.example.ordering.adapter.messaging;

import com.example.inventory.api.StockReserved;
import com.example.ordering.application.order.ConfirmOrderService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to inventory's {@link StockReserved} integration event by confirming the
 * order. Consuming another context only through its published {@code *-api} keeps
 * the contexts decoupled.
 */
@Component
public class StockReservedListener {

    private final ConfirmOrderService confirmOrder;

    public StockReservedListener(ConfirmOrderService confirmOrder) {
        this.confirmOrder = confirmOrder;
    }

    @EventListener
    public void on(StockReserved event) {
        confirmOrder.confirm(event.orderId());
    }
}
