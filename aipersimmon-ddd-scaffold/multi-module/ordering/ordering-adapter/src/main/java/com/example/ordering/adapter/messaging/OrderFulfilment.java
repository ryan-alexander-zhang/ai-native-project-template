package com.example.ordering.adapter.messaging;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.api.StockReleased;
import com.example.inventory.api.StockReservationFailed;
import com.example.inventory.api.StockReserved;
import com.example.ordering.application.fulfilment.OrderFulfilmentProcess;
import com.example.payment.api.PaymentAuthorized;
import com.example.payment.api.PaymentDeclined;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Inbound messaging adapter for the order-fulfilment flow: a thin delivery shell. It subscribes to
 * the cross-context integration events that advance the flow (from inventory and payment), unwraps
 * each to the order id (its correlation id) plus the payload the coordinator needs, and hands off to
 * the {@link OrderFulfilmentProcess}, which holds the coordination policy. No business logic
 * lives here — only the binding to Spring's event delivery, so swapping the transport (for example
 * to Kafka consumers) touches this class alone.
 *
 * <p>It references no domain type: it reads only other contexts' published contracts and passes the
 * triggering message's {@link CommandContext} on, so the next command stays correlated to the event
 * that caused it.
 */
@Component
public class OrderFulfilment {

    private final OrderFulfilmentProcess process;

    public OrderFulfilment(OrderFulfilmentProcess process) {
        this.process = process;
    }

    @EventListener
    public void onStockReserved(EventEnvelope<StockReserved> envelope) {
        StockReserved payload = envelope.payload();
        process.stockReserved(payload.orderId(), payload.reservationId(), CommandContext.of(envelope));
    }

    @EventListener
    public void onStockReservationFailed(EventEnvelope<StockReservationFailed> envelope) {
        StockReservationFailed payload = envelope.payload();
        process.stockReservationFailed(
                payload.orderId(), payload.code(), payload.reason(), CommandContext.of(envelope));
    }

    @EventListener
    public void onPaymentAuthorized(EventEnvelope<PaymentAuthorized> envelope) {
        process.paymentAuthorized(envelope.payload().orderId(), CommandContext.of(envelope));
    }

    @EventListener
    public void onPaymentDeclined(EventEnvelope<PaymentDeclined> envelope) {
        PaymentDeclined payload = envelope.payload();
        process.paymentDeclined(
                payload.orderId(), payload.code(), payload.reason(), CommandContext.of(envelope));
    }

    @EventListener
    public void onStockReleased(EventEnvelope<StockReleased> envelope) {
        StockReleased payload = envelope.payload();
        process.stockReleased(payload.orderId(), payload.reservationId(), CommandContext.of(envelope));
    }
}
