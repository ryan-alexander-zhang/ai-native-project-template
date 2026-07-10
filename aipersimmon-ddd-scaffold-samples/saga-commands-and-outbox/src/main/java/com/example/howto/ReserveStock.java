package com.example.howto;

import com.aipersimmon.ddd.cqrs.Command;

/** Command the saga sends to reserve stock for an order. Returns nothing. */
public record ReserveStock(String orderId, String sku, int quantity) implements Command<Void> {
}
