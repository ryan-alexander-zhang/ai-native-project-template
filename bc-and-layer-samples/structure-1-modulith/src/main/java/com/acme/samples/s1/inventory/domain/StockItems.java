package com.acme.samples.s1.inventory.domain;

import java.util.Optional;

/** Repository port for the StockItem aggregate. */
public interface StockItems {
    Optional<StockItem> bySku(String sku);
    void decrement(String sku, int qty);
}
