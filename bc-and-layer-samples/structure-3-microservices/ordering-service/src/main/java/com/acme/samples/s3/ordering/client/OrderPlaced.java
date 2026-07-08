package com.acme.samples.s3.ordering.client;

import java.util.List;

/** Wire contract this service PUBLISHES. Owned here; consumers keep their own copy. */
public record OrderPlaced(String orderId, String customerId, List<Line> lines) {
    public record Line(String sku, int qty, long unitPriceMinor) {}
}
