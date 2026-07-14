package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.saga.ProcessManager;
import com.aipersimmon.ddd.saga.SagaStore;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import org.springframework.stereotype.Component;

/**
 * The order-fulfilment process manager: the coordination policy of the
 * cross-context flow, expressed as plain application logic. It starts when an
 * order is placed, then drives the outcome once inventory responds — confirming
 * the order if stock was reserved, or cancelling it (compensation) if the
 * reservation failed. Its state lives in {@link OrderFulfilmentSaga}, loaded and
 * saved through the {@link SagaStore} port; the next steps go out through the
 * {@link CommandBus}. Holding this policy in one place is what makes the flow an
 * orchestration rather than a choreography.
 *
 * <p>How the triggering events physically arrive (a Spring {@code @EventListener},
 * a Kafka consumer, …) is a delivery concern kept out of this layer: an inbound
 * messaging adapter subscribes to them, unwraps each to its business correlation id
 * (the order id) plus the triggering message's {@link CommandContext}, and calls the
 * matching method here — the context is passed on to the command bus so the next
 * command stays correlated to the event that triggered it. So this coordinator never
 * sees a cross-context contract type and stays testable without any message transport.
 */
@Component
@ProcessManager
public class OrderFulfilmentProcessManager {

    private final SagaStore<OrderFulfilmentSaga> sagas;
    private final CommandBus commandBus;

    public OrderFulfilmentProcessManager(SagaStore<OrderFulfilmentSaga> sagas, CommandBus commandBus) {
        this.sagas = sagas;
        this.commandBus = commandBus;
    }

    /** An order was placed: start a new flow awaiting stock reservation. */
    public void onOrderPlaced(String orderId) {
        sagas.save(new OrderFulfilmentSaga(orderId));
    }

    /** Inventory reserved the stock: complete the flow and confirm the order. */
    public void onStockReserved(String orderId, CommandContext cause) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.reservationConfirmed();
            sagas.save(saga);
            commandBus.send(new ConfirmOrder(orderId), cause);
        });
    }

    /** Inventory could not reserve the stock: compensate by cancelling the order. */
    public void onStockReservationFailed(String orderId, CommandContext cause) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.reservationFailed();
            sagas.save(saga);
            commandBus.send(new CancelOrder(orderId), cause);
        });
    }
}
