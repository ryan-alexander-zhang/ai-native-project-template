package com.acme.samples.s1.inventory.domain;

/** Inventory aggregate root: available quantity for a SKU. */
public record StockItem(String sku, int available) {
    public boolean canReserve(int qty) {
        return available >= qty;
    }
}
