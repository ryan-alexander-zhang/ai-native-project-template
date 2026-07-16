package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.saga.ProcessManager;
import com.aipersimmon.ddd.saga.SagaStore;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.RequestPayment;
import com.example.ordering.application.order.RequestStockRelease;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.PaymentDeclineRef;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.domain.order.StockReleaseRef;
import org.springframework.stereotype.Component;

/**
 * The order-fulfilment process manager: the coordination policy of the cross-context flow, held in
 * one place so the flow is an orchestration rather than a choreography. Its state lives in
 * {@link OrderFulfilmentSaga}; the next steps go out as ordering commands through the
 * {@link CommandBus} (never as cross-context commands — the request events are published by the
 * respective use-case handlers, keeping bounded-context isolation intact).
 *
 * <p>The full flow it drives:
 * <pre>
 *   OrderPlaced
 *     └─ (inventory) StockReserved ──▶ RequestPayment
 *                                        ├─ PaymentAuthorized ─▶ ConfirmOrder ─▶ OrderConfirmed ─▶ COMPLETED
 *                                        └─ PaymentDeclined ──▶ RequestStockRelease
 *                                                                └─ StockReleased ─▶ CancelOrder(
 *                                                                        PaymentDeclinedAfterStockReleased)
 *                                                                        └─ OrderCancelled ─▶ ABORTED
 *     └─ (inventory) StockReservationFailed ─▶ CancelOrder(InventoryUnavailable) ─▶ OrderCancelled ─▶ ABORTED
 * </pre>
 *
 * <p>Crucially the saga reaches a terminal status only on {@code OrderConfirmed}/{@code OrderCancelled}
 * — the actual outcome — not the moment a confirm/cancel command is sent.
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

    /** Stock reserved: remember the reservation handle and ask the payment context to charge. */
    public void onStockReserved(String orderId, String reservationId, CommandContext cause) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.stockReserved(reservationId);
            sagas.save(saga);
            commandBus.send(new RequestPayment(orderId), cause);
        });
    }

    /** Stock could not be reserved: compensate by cancelling the order (no stock was held). */
    public void onStockReservationFailed(String orderId, String code, String reason, CommandContext cause) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.reservationFailed();
            sagas.save(saga);
            ReservationFailureRef failure = new ReservationFailureRef(orderId, new OrderId(orderId), code, reason);
            commandBus.send(new CancelOrder(orderId, new CancellationReason.InventoryUnavailable(failure)), cause);
        });
    }

    /** Payment authorised: confirm the order. */
    public void onPaymentAuthorized(String orderId, CommandContext cause) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.paymentAuthorized();
            sagas.save(saga);
            commandBus.send(new ConfirmOrder(orderId), cause);
        });
    }

    /** Payment declined: begin compensation by asking inventory to release the held stock. */
    public void onPaymentDeclined(String orderId, String code, String reason, CommandContext cause) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.paymentDeclined(code);
            sagas.save(saga);
            commandBus.send(new RequestStockRelease(orderId, saga.reservationId()), cause);
        });
    }

    /**
     * Stock released: only now cancel the order. The stock-release evidence and the earlier payment
     * decline are assembled here into the compensating cancellation reason — which the ordering
     * domain will accept precisely because both pieces of evidence are present and name this order.
     */
    public void onStockReleased(String orderId, String reservationId, CommandContext cause) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.stockReleased();
            sagas.save(saga);
            OrderId id = new OrderId(orderId);
            PaymentDeclineRef decline = new PaymentDeclineRef(orderId, id, saga.paymentDeclineCode());
            StockReleaseRef release = new StockReleaseRef(reservationId, id);
            CancellationReason reason = new CancellationReason.PaymentDeclinedAfterStockReleased(decline, release);
            commandBus.send(new CancelOrder(orderId, reason), cause);
        });
    }

    /** The order confirmed: end the flow successfully. */
    public void onOrderConfirmed(String orderId) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.orderConfirmed();
            sagas.save(saga);
        });
    }

    /** The order was cancelled: end the compensated flow. */
    public void onOrderCancelled(String orderId) {
        sagas.find(orderId).filter(OrderFulfilmentSaga::isActive).ifPresent(saga -> {
            saga.orderCancelled();
            sagas.save(saga);
        });
    }
}
