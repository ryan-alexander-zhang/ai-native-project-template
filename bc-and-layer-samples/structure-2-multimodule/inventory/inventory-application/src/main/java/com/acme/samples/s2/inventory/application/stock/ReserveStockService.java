package com.acme.samples.s2.inventory.application.stock;

import com.acme.samples.s2.inventory.api.StockResult;
import com.acme.samples.s2.inventory.domain.stock.StockItem;
import com.acme.samples.s2.inventory.domain.stock.StockItems;
import com.acme.samples.s2.ordering.api.OrderPlaced;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Reserves stock for an order; idempotent via the reservations inbox. The
 * {@link StockResult} integration event is written to the transactional outbox in
 * the SAME transaction (via {@link StockResultPublisher}) so the return leg of the
 * saga cannot be lost — the relay sends it to Kafka after commit (analysis-00005 §4).
 */
@Service
public class ReserveStockService {

    private final StockItems stock;
    private final Reservations reservations;
    private final StockResultPublisher publisher;

    public ReserveStockService(StockItems stock, Reservations reservations, StockResultPublisher publisher) {
        this.stock = stock;
        this.reservations = reservations;
        this.publisher = publisher;
    }

    @Transactional
    public void reserve(OrderPlaced event) {
        String firstSku = event.lines().get(0).sku();

        Optional<String> prior = reservations.outcome(event.orderId());
        if (prior.isPresent()) { // inbox replay: re-emit the stored outcome
            publisher.publish(new StockResult(event.orderId(), firstSku, "RESERVED".equals(prior.get()), "replay"));
            return;
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
        publisher.publish(new StockResult(event.orderId(), firstSku, ok, ok ? "reserved" : "insufficient stock"));
    }
}
