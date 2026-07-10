/**
 * How-to for orchestrating a flow with a saga: {@link com.example.howto.OrderFulfilment}
 * is the process manager that starts a flow when an order is placed, arms a
 * confirmation deadline, completes when stock is reserved, and compensates when the
 * deadline fires first. {@link com.example.howto.OrderFulfilmentSaga} is the
 * persisted state and its guarded lifecycle;
 * {@link com.example.howto.OrderFulfilmentSagaStore} persists it with optimistic
 * locking.
 */
package com.example.howto;
