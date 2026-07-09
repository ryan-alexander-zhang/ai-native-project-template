package com.example.inventory.application.stock;

import java.util.List;

/** Intent to reserve stock for an order: the order id and the lines to reserve. */
public record ReserveStockCommand(String orderId, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
