/**
 * The order-fulfilment flow's provider-agnostic contract in the application layer:
 * the business port {@link com.example.ordering.application.fulfilment.OrderFulfilmentProcess}
 * — the stable, intention-revealing entry the ordering context drives the flow through — and
 * {@link com.example.ordering.application.fulfilment.OrderFulfilmentStarter}, the application-layer
 * subscriber that bridges the context's own domain events into the port. The coordination policy
 * itself (the {@code ProcessDefinition}, its state, codecs, and the durable runtime driver) is the
 * provider's concern and lives in {@code com.example.ordering.process.fulfilment}, so this layer
 * carries no dependency on the process-manager framework. Cross-context event delivery is not here —
 * an inbound messaging adapter translates integration events and feeds the port.
 */
package com.example.ordering.application.fulfilment;
