package com.example.ordering.adapter.messaging;

import com.aipersimmon.ddd.saga.ProcessManager;
import com.aipersimmon.ddd.saga.SagaStore;
import com.example.contracts.StockReservationFailed;
import com.example.contracts.StockReserved;
import com.example.ordering.application.fulfilment.OrderFulfilmentSaga;
import com.example.ordering.application.order.CancelOrderService;
import com.example.ordering.application.order.ConfirmOrderService;
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
    private final ConfirmOrderService confirmOrder;
    private final CancelOrderService cancelOrder;

    public OrderFulfilment(SagaStore<OrderFulfilmentSaga> sagas,
                           ConfirmOrderService confirmOrder,
                           CancelOrderService cancelOrder) {
        this.sagas = sagas;
        this.confirmOrder = confirmOrder;
        this.cancelOrder = cancelOrder;
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
            confirmOrder.confirm(event.orderId());
        });
    }

    @EventListener
    public void onStockReservationFailed(StockReservationFailed event) {
        sagas.find(event.orderId()).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.reservationFailed();
            sagas.save(saga);
            cancelOrder.cancel(event.orderId());
        });
    }
}
