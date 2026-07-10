package com.example.inventory.adapter.messaging;

import com.example.inventory.application.stock.ReserveStockCommand;
import com.example.inventory.application.stock.ReserveStockService;
import com.example.contracts.OrderPlaced;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to ordering's {@link OrderPlaced} integration event by reserving stock.
 * It reads only the published contract of the ordering context, keeping the two
 * contexts decoupled.
 */
@Component
public class OrderPlacedListener {

    private final ReserveStockService reserveStock;

    public OrderPlacedListener(ReserveStockService reserveStock) {
        this.reserveStock = reserveStock;
    }

    @EventListener
    public void on(OrderPlaced event) {
        var lines = event.lines().stream()
                .map(line -> new ReserveStockCommand.Line(line.sku(), line.quantity()))
                .toList();
        reserveStock.reserve(new ReserveStockCommand(event.orderId(), lines));
    }
}
