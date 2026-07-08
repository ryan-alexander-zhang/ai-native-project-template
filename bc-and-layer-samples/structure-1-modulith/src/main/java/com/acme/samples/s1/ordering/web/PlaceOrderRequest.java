package com.acme.samples.s1.ordering.web;

import java.util.List;

public record PlaceOrderRequest(String customerId, List<Line> lines) {
    public record Line(String sku, int qty) {}
}
