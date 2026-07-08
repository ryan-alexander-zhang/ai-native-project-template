package com.acme.samples.s1.shared;

/**
 * Integration event: the Inventory context's decision for an order's stock.
 * Consumed by Ordering to confirm or cancel the order. Carried over Kafka.
 */
public record StockResult(String orderId, String sku, boolean reserved, String reason) {}
