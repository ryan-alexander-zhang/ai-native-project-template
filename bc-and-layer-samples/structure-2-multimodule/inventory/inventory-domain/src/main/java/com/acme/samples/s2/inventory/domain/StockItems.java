package com.acme.samples.s2.inventory.domain;

import java.util.Optional;

/** Repository port for the StockItem aggregate. */
public interface StockItems {
    Optional<StockItem> bySku(String sku);
    void decrement(String sku, int qty);
}
