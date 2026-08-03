package com.example.samples.s21.inventory.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port. */
@Repository
public interface StockItems {

  void save(StockItem item);

  Optional<StockItem> find(StockLocation location);
}
