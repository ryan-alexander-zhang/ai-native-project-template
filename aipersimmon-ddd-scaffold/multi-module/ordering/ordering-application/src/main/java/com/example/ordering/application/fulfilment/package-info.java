/**
 * The order-fulfilment flow: its coordination policy
 * ({@link com.example.ordering.application.fulfilment.OrderFulfilmentProcessManager})
 * and the central, persisted state it drives
 * ({@link com.example.ordering.application.fulfilment.OrderFulfilmentSaga}). Together
 * they confirm an order once inventory reserves its stock, or cancel it
 * (compensation) if the reservation fails. Event delivery is not here — an inbound
 * messaging adapter feeds the process manager.
 */
package com.example.ordering.application.fulfilment;
