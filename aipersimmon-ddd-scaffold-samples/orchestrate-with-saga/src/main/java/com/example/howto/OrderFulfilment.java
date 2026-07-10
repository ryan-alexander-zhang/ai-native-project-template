package com.example.howto;

import com.aipersimmon.ddd.saga.Deadline;
import com.aipersimmon.ddd.saga.DeadlineHandler;
import com.aipersimmon.ddd.saga.DeadlineScheduler;
import com.aipersimmon.ddd.saga.ProcessManager;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The process manager coordinating order fulfilment from one place. It reacts to
 * the flow's inputs — an order placed, stock reserved — and to the confirmation
 * deadline, loading the saga by order id, letting it react, and saving it back in
 * one transaction each time.
 *
 * <p>It implements {@link DeadlineHandler}, so the saga starter's scheduler calls
 * {@link #onDeadline} when the confirmation deadline fires.
 */
@Service
@ProcessManager
public class OrderFulfilment implements DeadlineHandler {

    /** Name distinguishing this saga's confirmation timeout. */
    static final String CONFIRM_TIMEOUT = "confirm-timeout";

    private final OrderFulfilmentSagaStore store;
    private final DeadlineScheduler deadlines;
    private final Duration confirmTimeout;

    public OrderFulfilment(OrderFulfilmentSagaStore store,
                           DeadlineScheduler deadlines,
                           @Value("${howto.saga.confirm-timeout-ms:600000}") long confirmTimeoutMs) {
        this.store = store;
        this.deadlines = deadlines;
        this.confirmTimeout = Duration.ofMillis(confirmTimeoutMs);
    }

    /** An order was placed: start the flow and arm the confirmation deadline. */
    @Transactional
    public void onOrderPlaced(String orderId, String sku) {
        store.save(new OrderFulfilmentSaga(orderId, sku));
        deadlines.schedule(new Deadline(orderId, CONFIRM_TIMEOUT, Instant.now().plus(confirmTimeout)));
    }

    /** Stock was reserved in time: complete the flow and disarm the deadline. */
    @Transactional
    public void onStockReserved(String orderId) {
        store.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.confirmReservation();
            store.save(saga);
            deadlines.cancel(orderId, CONFIRM_TIMEOUT);
        });
    }

    /** The confirmation deadline fired: compensate and end the flow, unless it already ended. */
    @Override
    @Transactional
    public void onDeadline(Deadline deadline) {
        store.find(deadline.correlationId()).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.expire();
            store.save(saga);
        });
    }
}
