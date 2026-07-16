package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.saga.SagaState;
import com.aipersimmon.ddd.saga.SagaStatus;

/**
 * State of one order-fulfilment flow, correlated by the order id. It is a small state machine over
 * the fulfilment steps, holding the two facts the flow must carry across events: the
 * {@code reservationId} inventory issued (so the stock can be released later) and the payment
 * decline code (so the eventual cancellation can name why).
 *
 * <p>Two properties are deliberate:
 * <ul>
 *   <li><b>It ends only on a confirmed outcome.</b> {@link #orderConfirmed()} /
 *       {@link #orderCancelled()} are what move it to a terminal status — never the mere
 *       <em>sending</em> of a confirm/cancel command. So a command that is sent but fails leaves the
 *       saga still active rather than falsely terminal.</li>
 *   <li><b>Compensation is ordered.</b> A payment decline drives it through
 *       {@link Step#AWAITING_STOCK_RELEASE} before {@link Step#AWAITING_ORDER_CANCELLATION}: the
 *       order is not cancelled until the stock it held has actually been released.</li>
 * </ul>
 */
public class OrderFulfilmentSaga extends SagaState {

    /** Which response the flow is currently waiting for. */
    public enum Step {
        AWAITING_STOCK,
        AWAITING_PAYMENT,
        AWAITING_STOCK_RELEASE,
        AWAITING_ORDER_CONFIRMATION,
        AWAITING_ORDER_CANCELLATION
    }

    private Step step;
    private String reservationId;
    private String paymentDeclineCode;

    /** Start a new flow for an order awaiting stock reservation. */
    public OrderFulfilmentSaga(String orderId) {
        super(orderId);
        this.step = Step.AWAITING_STOCK;
    }

    /** Rehydrate a persisted flow (used by a durable {@code SagaStore}). */
    public OrderFulfilmentSaga(
            String orderId, SagaStatus status, long version,
            Step step, String reservationId, String paymentDeclineCode) {
        super(orderId, status, version);
        this.step = step;
        this.reservationId = reservationId;
        this.paymentDeclineCode = paymentDeclineCode;
    }

    public Step step() {
        return step;
    }

    public String reservationId() {
        return reservationId;
    }

    public String paymentDeclineCode() {
        return paymentDeclineCode;
    }

    /** Stock was reserved: remember its handle and wait for payment. */
    public void stockReserved(String reservationId) {
        requireStep(Step.AWAITING_STOCK);
        this.reservationId = reservationId;
        this.step = Step.AWAITING_PAYMENT;
    }

    /** Stock could not be reserved: compensate straight to cancellation (nothing was held). */
    public void reservationFailed() {
        requireStep(Step.AWAITING_STOCK);
        startCompensation();
        this.step = Step.AWAITING_ORDER_CANCELLATION;
    }

    /** Payment authorised: wait for the order to confirm. */
    public void paymentAuthorized() {
        requireStep(Step.AWAITING_PAYMENT);
        this.step = Step.AWAITING_ORDER_CONFIRMATION;
    }

    /** Payment declined: compensate by releasing the held stock first. */
    public void paymentDeclined(String declineCode) {
        requireStep(Step.AWAITING_PAYMENT);
        startCompensation();
        this.paymentDeclineCode = declineCode;
        this.step = Step.AWAITING_STOCK_RELEASE;
    }

    /** The held stock was released: now the order may be cancelled. */
    public void stockReleased() {
        requireStep(Step.AWAITING_STOCK_RELEASE);
        this.step = Step.AWAITING_ORDER_CANCELLATION;
    }

    /** The order confirmed: the flow ends successfully. */
    public void orderConfirmed() {
        requireStep(Step.AWAITING_ORDER_CONFIRMATION);
        complete();
    }

    /** The order was cancelled: the compensated flow ends. */
    public void orderCancelled() {
        requireStep(Step.AWAITING_ORDER_CANCELLATION);
        abort();
    }

    private void requireStep(Step expected) {
        if (step != expected) {
            throw new IllegalStateException(
                    "fulfilment saga " + correlationId() + " is at " + step + ", expected " + expected);
        }
    }
}
