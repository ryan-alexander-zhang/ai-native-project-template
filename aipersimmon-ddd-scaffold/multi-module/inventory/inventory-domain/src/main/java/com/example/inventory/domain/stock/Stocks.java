package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** Repository port for the Stock aggregate; implemented in the infrastructure layer. */
@Repository
public interface Stocks {

  void save(Stock stock);

  Optional<Stock> findBySku(Sku sku);
}
