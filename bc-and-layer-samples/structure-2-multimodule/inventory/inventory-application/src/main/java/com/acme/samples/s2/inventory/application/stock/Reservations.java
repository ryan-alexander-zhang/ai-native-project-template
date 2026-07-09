package com.acme.samples.s2.inventory.application.stock;

import java.util.Optional;

/**
 * Inbox port: record and look up the reservation decision per order (idempotency),
 * and support release (compensation) — analysis-00005 §4 / §八.
 */
public interface Reservations {

    /** Reservation record with enough detail to release the held stock. */
    record Reservation(String sku, int qty, String outcome) {}

    Optional<String> outcome(String orderId);

    void record(String orderId, String sku, int qty, String outcome);

    Optional<Reservation> find(String orderId);

    /** Mark a reservation released so a redelivered cancellation does not double-release. */
    void markReleased(String orderId);
}
