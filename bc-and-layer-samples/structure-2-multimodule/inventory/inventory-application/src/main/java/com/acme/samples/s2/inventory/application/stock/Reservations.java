package com.acme.samples.s2.inventory.application.stock;

import java.util.Optional;

/** Inbox port: record and look up the reservation decision per order (idempotency). */
public interface Reservations {
    Optional<String> outcome(String orderId);
    void record(String orderId, String sku, int qty, String outcome);
}
