package com.example.inventory.adapter.messaging;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.inventory.application.stock.ReserveStock;
import com.example.ordering.api.OrderPlaced;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to ordering's {@link OrderPlaced} integration event by sending a
 * {@link ReserveStock} command through the command bus. It reads only the published
 * contract of the ordering context, keeping the two contexts decoupled.
 */
@Component
public class OrderPlacedListener {

    private final CommandBus commandBus;

    public OrderPlacedListener(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @EventListener
    public void on(OrderPlaced event) {
        var lines = event.lines().stream()
                .map(line -> new ReserveStock.Line(line.sku(), line.quantity()))
                .toList();
        commandBus.send(new ReserveStock(event.orderId(), lines));
    }
}
