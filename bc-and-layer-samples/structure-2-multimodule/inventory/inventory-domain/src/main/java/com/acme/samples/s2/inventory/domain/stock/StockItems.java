package com.acme.samples.s2.inventory.domain.stock;

import java.util.Optional;

/** Repository port for the StockItem aggregate. */
public interface StockItems {
    Optional<StockItem> bySku(String sku);
    void decrement(String sku, int qty);
    void increment(String sku, int qty);   // release reserved stock (compensation)
}
