package com.example.howto;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.saga.ProcessManager;
import com.aipersimmon.ddd.saga.SagaStore;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The process manager. It coordinates order fulfilment by <b>sending commands</b>
 * through the {@link CommandBus} — never by calling handlers or writing state
 * directly — and reacts to the integration events the command handlers publish
 * through the outbox and the relay redelivers in process.
 *
 * <ol>
 *   <li>Order placed (in-process domain event) → start the saga, send {@code ReserveStock}.</li>
 *   <li>Stock reserved (from the outbox, in process) → send {@code ConfirmOrder}, complete.</li>
 *   <li>Reservation failed (from the outbox) → send {@code CancelOrder} (compensate), abort.</li>
 * </ol>
 *
 * Because outbox delivery is at-least-once, a reaction may arrive more than once;
 * the saga's guarded lifecycle ({@code isActive}) makes that a no-op.
 */
@Component
@ProcessManager
public class OrderFulfilment {

    private final CommandBus commandBus;
    private final SagaStore<FulfilmentSaga> sagas;

    public OrderFulfilment(CommandBus commandBus, SagaStore<FulfilmentSaga> sagas) {
        this.commandBus = commandBus;
        this.sagas = sagas;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        sagas.save(new FulfilmentSaga(event.orderId()));
        commandBus.send(new ReserveStock(event.orderId(), event.sku(), 1));
    }

    @EventListener
    public void onStockReserved(StockReserved event) {
        sagas.find(event.orderId()).filter(FulfilmentSaga::isActive).ifPresent(saga -> {
            commandBus.send(new ConfirmOrder(event.orderId()));
            saga.reservationConfirmed();
            sagas.save(saga);
        });
    }

    @EventListener
    public void onStockReservationFailed(StockReservationFailed event) {
        sagas.find(event.orderId()).filter(FulfilmentSaga::isActive).ifPresent(saga -> {
            commandBus.send(new CancelOrder(event.orderId()));
            saga.reservationFailed();
            sagas.save(saga);
        });
    }
}
