package com.acme.samples.s3.inventory.client;

/** Wire contract this service PUBLISHES. */
public record StockResult(String orderId, String sku, boolean reserved, String reason) {}
