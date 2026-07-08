package com.acme.samples.s2.ordering.adapter.web;

import java.util.List;

public record PlaceOrderRequest(String customerId, List<Line> lines) {
    public record Line(String sku, int qty) {}
}
