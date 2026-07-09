package com.acme.samples.s3.inventory.app.stock;

import java.util.Optional;

/** Inbox port: reservation decision per order (idempotency). */
public interface Reservations {
    Optional<String> outcome(String orderId);
    void record(String orderId, String sku, int qty, String outcome);
}
