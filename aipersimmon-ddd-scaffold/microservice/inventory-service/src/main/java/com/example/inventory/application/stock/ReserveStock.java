package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.Command;
import java.util.List;

/** Command to reserve stock for an order: the order id and the lines to reserve. No result. */
public record ReserveStock(String orderId, List<Line> lines) implements Command<Void> {

    public record Line(String sku, int quantity) {
    }
}
