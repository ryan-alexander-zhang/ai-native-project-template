package com.acme.samples.s3.inventory.client;

import java.util.List;

/** Wire contract this service CONSUMES (local copy of Ordering's published event). */
public record OrderPlaced(String orderId, String customerId, List<Line> lines) {
    public record Line(String sku, int qty, long unitPriceMinor) {}
}
