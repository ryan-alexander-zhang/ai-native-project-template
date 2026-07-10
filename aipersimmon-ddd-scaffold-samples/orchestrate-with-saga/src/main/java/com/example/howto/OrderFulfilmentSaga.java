package com.example.howto;

import com.aipersimmon.ddd.saga.SagaState;
import com.aipersimmon.ddd.saga.SagaStatus;

/**
 * State of one order-fulfilment flow, correlated by the order id. Its only flow
 * datum is the sku awaiting reservation. The lifecycle methods express the two
 * outcomes: stock reserved in time ({@link #confirmReservation()} completes) or the
 * confirmation deadline firing first ({@link #expire()} compensates and aborts).
 */
public class OrderFulfilmentSaga extends SagaState {

    private final String sku;

    /** Start a new flow for an order. */
    public OrderFulfilmentSaga(String orderId, String sku) {
        super(orderId);
        this.sku = sku;
    }

    /** Rehydrate a persisted flow. */
    public OrderFulfilmentSaga(String orderId, SagaStatus status, long version, String sku) {
        super(orderId, status, version);
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }

    /** Stock was reserved before the deadline: the flow ends successfully. */
    public void confirmReservation() {
        complete();
    }

    /**
     * The confirmation deadline fired before stock was reserved: run the
     * compensating action (here, conceptually releasing any hold) and end the flow.
     */
    public void expire() {
        startCompensation();
        // A real flow would release the reservation / notify here.
        abort();
    }
}
