package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import java.util.List;

/**
 * Command to place an order: the customer and the lines, in primitive input form.
 * Dispatched through the command bus; its result is the new order id.
 */
public record PlaceOrder(String customerId, List<Line> lines) implements Command<String> {

    public record Line(String sku, int quantity, long unitAmountMinor, String currency) {
    }
}
