package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The stock port. */
@Repository
public interface Stocks {

  Optional<Stock> findBySku(Sku sku);

  void save(Stock stock);
}
