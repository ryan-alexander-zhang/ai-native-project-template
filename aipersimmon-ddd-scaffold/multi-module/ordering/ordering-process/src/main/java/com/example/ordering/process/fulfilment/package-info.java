/**
 * The order-fulfilment durable Process Manager provider for the ordering context, on the JDBC
 * runtime. It holds the execution code that is specific to this provider, kept out of the
 * application layer so the framework dependency is compile-fenced there:
 *
 * <ul>
 *   <li>{@link com.example.ordering.process.fulfilment.OrderFulfilmentDefinition} — the pure,
 *       deterministic coordination policy ({@code ProcessDefinition});
 *   <li>{@link com.example.ordering.process.fulfilment.OrderFulfilmentState} / {@link
 *       com.example.ordering.process.fulfilment.OrderFulfilmentInput} — the flow's persisted state
 *       and the facts it reacts to;
 *   <li>{@link com.example.ordering.process.fulfilment.OrderFulfilmentCodecs} — the persistence
 *       codecs for state, inputs, and command effects;
 *   <li>{@link com.example.ordering.process.fulfilment.RuntimeOrderFulfilmentProcess} — the
 *       runtime-backed implementation of the application's {@link
 *       com.example.ordering.application.fulfilment.OrderFulfilmentProcess} business port.
 * </ul>
 *
 * The provider-agnostic contract (the business port) lives in the application layer; swapping this
 * provider replaces this module without touching the ordering domain or application.
 */
package com.example.ordering.process.fulfilment;
