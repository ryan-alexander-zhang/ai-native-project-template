package com.acme.samples.s3.inventory.domain;

import java.util.Optional;

public interface StockItems {
    Optional<StockItem> bySku(String sku);
    void decrement(String sku, int qty);
}
