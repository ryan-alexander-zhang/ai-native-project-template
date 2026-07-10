package com.example.howto;

import com.aipersimmon.ddd.saga.SagaState;
import com.aipersimmon.ddd.saga.SagaStatus;

/** Central state of one order-fulfilment flow, correlated by order id. */
public class FulfilmentSaga extends SagaState {

    public FulfilmentSaga(String orderId) {
        super(orderId);
    }

    public FulfilmentSaga(String orderId, SagaStatus status, long version) {
        super(orderId, status, version);
    }

    public void reservationConfirmed() {
        complete();
    }

    public void reservationFailed() {
        startCompensation();
        abort();
    }
}
