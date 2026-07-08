package com.acme.samples.s2.inventory.api;

/** Integration event published by Inventory; consumed by Ordering. */
public record StockResult(String orderId, String sku, boolean reserved, String reason) {}
