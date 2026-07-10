package com.example.ordering.adapter.web;

import com.example.ordering.application.order.PlaceOrderCommand;
import java.util.List;

/** Web request body for placing an order; maps to the application command. */
public record PlaceOrderRequest(String customerId, List<Line> lines) {

    public record Line(String sku, int quantity, long unitAmountMinor, String currency) {
    }

    public PlaceOrderCommand toCommand() {
        List<PlaceOrderCommand.Line> commandLines = lines.stream()
                .map(line -> new PlaceOrderCommand.Line(
                        line.sku(), line.quantity(), line.unitAmountMinor(), line.currency()))
                .toList();
        return new PlaceOrderCommand(customerId, commandLines);
    }
}
