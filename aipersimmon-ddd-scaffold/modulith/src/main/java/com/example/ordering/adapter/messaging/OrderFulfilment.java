package com.example.ordering.adapter.messaging;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.api.StockReservationFailed;
import com.example.inventory.api.StockReserved;
import com.example.ordering.application.fulfilment.OrderFulfilmentProcessManager;
import com.example.ordering.domain.order.OrderPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Inbound messaging adapter for the order-fulfilment flow: a thin delivery shell.
 * It subscribes to the events that advance the flow, unwraps each to the order id
 * (its correlation id), and hands off to the {@link OrderFulfilmentProcessManager},
 * which holds the coordination policy. No business or coordination logic lives
 * here — only the binding to Spring's event delivery, so swapping the transport
 * (for example to a Kafka consumer) touches this class alone.
 *
 * <p>It starts the flow on the internal {@link OrderPlacedEvent} (published before
 * the outgoing {@code OrderPlaced} integration event), so the saga always exists
 * before inventory's response arrives. Cross-context responses are read only
 * through inventory's published {@code *-api} events.
 */
@Component
public class OrderFulfilment {

    private final OrderFulfilmentProcessManager process;

    public OrderFulfilment(OrderFulfilmentProcessManager process) {
        this.process = process;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        process.onOrderPlaced(event.orderId().value());
    }

    @EventListener
    public void onStockReserved(EventEnvelope<StockReserved> envelope) {
        process.onStockReserved(envelope.payload().orderId(), CommandContext.of(envelope));
    }

    @EventListener
    public void onStockReservationFailed(EventEnvelope<StockReservationFailed> envelope) {
        process.onStockReservationFailed(envelope.payload().orderId(), CommandContext.of(envelope));
    }
}
