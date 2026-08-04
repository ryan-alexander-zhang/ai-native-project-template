package com.example.samples.s24.inventory.domain;

import java.util.Optional;

/** The stock repository. */
public interface StockItems {

  Optional<StockItem> find(Sku sku);

  void save(StockItem item);
}
