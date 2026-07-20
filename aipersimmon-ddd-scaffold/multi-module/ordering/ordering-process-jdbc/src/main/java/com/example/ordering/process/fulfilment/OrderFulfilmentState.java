package com.example.ordering.application.fulfilment;

/**
 * The business state of one order-fulfilment flow, carried across inputs by the durable runtime. It
 * holds the two facts the flow must remember: the {@code reservationId} inventory issued (so the
 * stock can be released on compensation) and the payment decline code (so the eventual cancellation
 * can name why). It is a plain immutable value — the runtime persists it via a state codec, and the
 * runtime lifecycle (RUNNING / COMPENSATING / COMPLETED) is tracked separately, not in this state.
 *
 * @param orderId            the order this flow coordinates (its business key)
 * @param step               the business step the flow is waiting at
 * @param reservationId      the inventory reservation handle, once stock is reserved
 * @param paymentDeclineCode the payment decline code, once payment is declined
 */
public record OrderFulfilmentState(String orderId, Step step, String reservationId, String paymentDeclineCode) {

    /** Which response the flow is currently waiting for. */
    public enum Step {
        AWAITING_STOCK,
        AWAITING_PAYMENT,
        AWAITING_STOCK_RELEASE,
        AWAITING_ORDER_CONFIRMATION,
        AWAITING_ORDER_CANCELLATION,
        CONFIRMED,
        CANCELLED
    }

    public OrderFulfilmentState withStep(Step next) {
        return new OrderFulfilmentState(orderId, next, reservationId, paymentDeclineCode);
    }

    public OrderFulfilmentState reserved(String reservationId, Step next) {
        return new OrderFulfilmentState(orderId, next, reservationId, paymentDeclineCode);
    }

    public OrderFulfilmentState declined(String declineCode, Step next) {
        return new OrderFulfilmentState(orderId, next, reservationId, declineCode);
    }
}
