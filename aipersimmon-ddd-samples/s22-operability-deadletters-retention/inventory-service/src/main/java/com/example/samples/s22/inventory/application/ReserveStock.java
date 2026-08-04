package com.example.samples.s22.inventory.application;

import com.aipersimmon.ddd.cqrs.Command;

/** Reserve stock for an order. */
public record ReserveStock(String orderId, String sku, int quantity) implements Command<Void> {}
