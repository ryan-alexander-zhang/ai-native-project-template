/**
 * How-to for an orchestration saga that drives a flow by sending commands with
 * reliable outbound events. {@link com.example.howto.OrderService} places an order
 * and emits {@link com.example.howto.OrderPlacedEvent}, which starts
 * {@link com.example.howto.OrderFulfilment} (the process manager). The saga sends
 * {@link com.example.howto.ReserveStock}, {@link com.example.howto.ConfirmOrder}, and
 * {@link com.example.howto.CancelOrder} through the command bus; each handler
 * publishes its integration event ({@link com.example.howto.StockReserved},
 * {@link com.example.howto.StockReservationFailed},
 * {@link com.example.howto.OrderConfirmed}) reliably through the outbox, in the same
 * transaction as its write. The outbox relay redelivers those events in process to
 * advance the saga.
 */
package com.example.howto;
