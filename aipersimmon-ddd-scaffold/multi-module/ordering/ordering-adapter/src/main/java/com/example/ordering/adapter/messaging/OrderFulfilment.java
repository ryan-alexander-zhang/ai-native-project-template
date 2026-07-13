package com.example.ordering.adapter.messaging;

import com.example.inventory.api.StockReservationFailed;
import com.example.inventory.api.StockReserved;
import com.example.ordering.application.fulfilment.OrderFulfilmentProcessManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Inbound messaging adapter for the order-fulfilment flow: a thin delivery shell.
 * It subscribes to the cross-context integration events that advance the flow,
 * unwraps each to the order id (its correlation id), and hands off to the
 * {@link OrderFulfilmentProcessManager}, which holds the coordination policy. No
 * business or coordination logic lives here — only the binding to Spring's event
 * delivery, so swapping the transport (for example to a Kafka consumer) touches
 * this class alone.
 *
 * <p>Cross-context responses are read only through inventory's published
 * {@code *-api} integration events, so this adapter references no domain type. The
 * flow is <em>started</em> from the context's own {@code OrderPlacedEvent} by an
 * application-layer subscriber ({@code OrderFulfilmentStarter}), keeping the
 * domain-event subscription out of the adapter layer.
 */
@Component
public class OrderFulfilment {

    private final OrderFulfilmentProcessManager process;

    public OrderFulfilment(OrderFulfilmentProcessManager process) {
        this.process = process;
    }

    @EventListener
    public void onStockReserved(StockReserved event) {
        process.onStockReserved(event.orderId());
    }

    @EventListener
    public void onStockReservationFailed(StockReservationFailed event) {
        process.onStockReservationFailed(event.orderId());
    }
}
