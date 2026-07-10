/**
 * Shared cross-service integration-event contracts, published to and consumed from
 * Kafka: {@link com.example.contracts.OrderPlaced},
 * {@link com.example.contracts.StockReserved}, and
 * {@link com.example.contracts.StockReservationFailed}. Both services depend on this
 * module so each event has one identical type on both sides of the broker.
 */
package com.example.contracts;
