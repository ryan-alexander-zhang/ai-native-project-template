package com.example.ordering.adapter.messaging;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.saga.ProcessManager;
import com.aipersimmon.ddd.saga.SagaStore;
import com.example.contracts.StockReservationFailed;
import com.example.contracts.StockReserved;
import com.example.ordering.application.fulfilment.OrderFulfilmentSaga;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.domain.order.OrderPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The order-fulfilment process manager. It coordinates the cross-context flow from
 * one place, holding its state in a {@link OrderFulfilmentSaga}: it starts when an
 * order is placed, and then drives the outcome when inventory responds — confirming
 * the order if stock was reserved, or cancelling it (compensation) if the
 * reservation failed. This is the orchestration counterpart to a choreography, where
 * each event handler would react independently with no shared view of the flow.
 *
 * <p>It starts on the internal {@link OrderPlacedEvent} (published before the
 * outgoing {@code OrderPlaced} integration event), so the saga always exists before
 * inventory's response arrives. Cross-context responses are read only through
 * inventory's published {@code *-api} events.
 */
@Component
@ProcessManager
public class OrderFulfilment {

    private final SagaStore<OrderFulfilmentSaga> sagas;
    private final CommandBus commandBus;

    public OrderFulfilment(SagaStore<OrderFulfilmentSaga> sagas, CommandBus commandBus) {
        this.sagas = sagas;
        this.commandBus = commandBus;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        sagas.save(new OrderFulfilmentSaga(event.orderId().value()));
    }

    @EventListener
    public void onStockReserved(StockReserved event) {
        sagas.find(event.orderId()).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.reservationConfirmed();
            sagas.save(saga);
            commandBus.send(new ConfirmOrder(event.orderId()));
        });
    }

    @EventListener
    public void onStockReservationFailed(StockReservationFailed event) {
        sagas.find(event.orderId()).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.reservationFailed();
            sagas.save(saga);
            commandBus.send(new CancelOrder(event.orderId()));
        });
    }
}
