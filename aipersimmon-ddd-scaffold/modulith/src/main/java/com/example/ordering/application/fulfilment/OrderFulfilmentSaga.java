package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.saga.SagaState;
import com.aipersimmon.ddd.saga.SagaStatus;

/**
 * State of one order-fulfilment flow, correlated by the order id. It starts when an
 * order is placed and ends one of two ways: the reservation is confirmed
 * ({@link #reservationConfirmed()} completes it) or it fails
 * ({@link #reservationFailed()} compensates and aborts it). Holding this state in
 * one place — rather than reacting to each event independently — is what makes the
 * flow an orchestration rather than a choreography.
 */
public class OrderFulfilmentSaga extends SagaState {

    /** Start a new flow for an order awaiting stock reservation. */
    public OrderFulfilmentSaga(String orderId) {
        super(orderId);
    }

    /** Rehydrate a persisted flow. */
    public OrderFulfilmentSaga(String orderId, SagaStatus status, long version) {
        super(orderId, status, version);
    }

    /** Stock was reserved: the flow ends successfully. */
    public void reservationConfirmed() {
        complete();
    }

    /** Stock could not be reserved: compensate (the coordinator cancels the order) and end. */
    public void reservationFailed() {
        startCompensation();
        abort();
    }
}
