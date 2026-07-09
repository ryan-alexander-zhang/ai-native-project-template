package com.acme.samples.s2.inventory.domain.stock;

/** Inventory aggregate root. A single-object aggregate (no internal entities). */
public record StockItem(String sku, int available) {
    public boolean canReserve(int qty) { return available >= qty; }
}
