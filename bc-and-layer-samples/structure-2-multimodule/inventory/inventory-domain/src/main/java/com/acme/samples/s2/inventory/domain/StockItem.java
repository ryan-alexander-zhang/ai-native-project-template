package com.acme.samples.s2.inventory.domain;

/** Inventory aggregate root. */
public record StockItem(String sku, int available) {
    public boolean canReserve(int qty) {
        return available >= qty;
    }
}
