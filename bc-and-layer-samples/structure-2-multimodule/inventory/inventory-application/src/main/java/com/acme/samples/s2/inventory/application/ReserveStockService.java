package com.acme.samples.s2.inventory.application;

import com.acme.samples.s2.inventory.api.StockResult;
import com.acme.samples.s2.inventory.domain.stock.StockItem;
import com.acme.samples.s2.inventory.domain.stock.StockItems;
import com.acme.samples.s2.ordering.api.OrderPlaced;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Reserves stock for an order; idempotent via the reservations inbox. */
@Service
public class ReserveStockService {

    private final StockItems stock;
    private final Reservations reservations;

    public ReserveStockService(StockItems stock, Reservations reservations) {
        this.stock = stock;
        this.reservations = reservations;
    }

    @Transactional
    public StockResult reserve(OrderPlaced event) {
        String firstSku = event.lines().get(0).sku();

        Optional<String> prior = reservations.outcome(event.orderId());
        if (prior.isPresent()) { // inbox replay
            return new StockResult(event.orderId(), firstSku, "RESERVED".equals(prior.get()), "replay");
        }

        boolean ok = true;
        for (OrderPlaced.Line line : event.lines()) {
            StockItem item = stock.bySku(line.sku()).orElse(null);
            if (item == null || !item.canReserve(line.qty())) { ok = false; break; }
        }
        if (ok) {
            for (OrderPlaced.Line line : event.lines()) {
                stock.decrement(line.sku(), line.qty());
            }
        }

        int qty = event.lines().stream().mapToInt(OrderPlaced.Line::qty).sum();
        reservations.record(event.orderId(), firstSku, qty, ok ? "RESERVED" : "REJECTED");
        return new StockResult(event.orderId(), firstSku, ok, ok ? "reserved" : "insufficient stock");
    }
}
