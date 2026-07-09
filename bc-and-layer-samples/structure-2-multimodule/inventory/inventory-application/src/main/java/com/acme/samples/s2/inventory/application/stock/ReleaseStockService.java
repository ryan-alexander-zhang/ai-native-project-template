package com.acme.samples.s2.inventory.application.stock;

import com.acme.samples.s2.inventory.application.stock.Reservations.Reservation;
import com.acme.samples.s2.inventory.domain.stock.StockItems;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compensation (analysis-00005 §八/G6): releases stock reserved for an order that
 * Ordering cancelled (e.g. a timed-out saga). Idempotent — only a still-RESERVED
 * reservation is released, then marked RELEASED, so redelivered OrderCancelled
 * messages converge.
 *
 * <p>Known limitation (documented, sample scope): a reservation stores only the
 * first sku + total qty, and an OrderCancelled that races ahead of the original
 * OrderPlaced finds no reservation and cannot pre-empt it. Full handling
 * (per-line release, cancellation tombstones) is out of scope here.
 */
@Service
public class ReleaseStockService {

    private final StockItems stock;
    private final Reservations reservations;

    public ReleaseStockService(StockItems stock, Reservations reservations) {
        this.stock = stock;
        this.reservations = reservations;
    }

    @Transactional
    public void release(String orderId) {
        Reservation reservation = reservations.find(orderId).orElse(null);
        if (reservation == null || !"RESERVED".equals(reservation.outcome())) {
            return;   // never reserved, already released, or was rejected — nothing to do
        }
        stock.increment(reservation.sku(), reservation.qty());
        reservations.markReleased(orderId);
    }
}
