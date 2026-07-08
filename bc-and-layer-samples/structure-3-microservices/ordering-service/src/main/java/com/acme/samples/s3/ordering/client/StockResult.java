package com.acme.samples.s3.ordering.client;

/** Wire contract this service CONSUMES (local copy of Inventory's published event). */
public record StockResult(String orderId, String sku, boolean reserved, String reason) {}
