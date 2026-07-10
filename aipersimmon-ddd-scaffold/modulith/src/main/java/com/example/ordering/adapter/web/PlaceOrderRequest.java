package com.example.ordering.adapter.web;

import com.example.ordering.application.order.PlaceOrder;
import java.util.List;

/** Web request body for placing an order; maps to the {@link PlaceOrder} command. */
public record PlaceOrderRequest(String customerId, List<Line> lines) {

    public record Line(String sku, int quantity, long unitAmountMinor, String currency) {
    }

    public PlaceOrder toCommand() {
        List<PlaceOrder.Line> commandLines = lines.stream()
                .map(line -> new PlaceOrder.Line(
                        line.sku(), line.quantity(), line.unitAmountMinor(), line.currency()))
                .toList();
        return new PlaceOrder(customerId, commandLines);
    }
}
