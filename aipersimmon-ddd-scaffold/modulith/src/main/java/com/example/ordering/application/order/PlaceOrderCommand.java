package com.example.ordering.application.order;

import java.util.List;

/** Intent to place an order: the customer and the lines, in primitive input form. */
public record PlaceOrderCommand(String customerId, List<Line> lines) {

    public record Line(String sku, int quantity, long unitAmountMinor, String currency) {
    }
}
