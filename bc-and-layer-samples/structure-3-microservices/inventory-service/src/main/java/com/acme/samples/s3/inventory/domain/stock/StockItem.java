package com.acme.samples.s3.inventory.domain;

public record StockItem(String sku, int available) {
    public boolean canReserve(int qty) { return available >= qty; }
}
