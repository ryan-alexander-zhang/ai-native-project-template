package com.example.samples.s22.inventory.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s22.inventory.domain.Sku;
import com.example.samples.s22.inventory.domain.StockItems;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The read that answers "did the quarantined message ever land". It matters that it goes through
 * the bus: an operator checking after a replay wants the same boundary the replay itself ran in,
 * not whatever a controller's bare repository call happened to see.
 */
@Component
class FindStockHandler implements QueryHandler<FindStock, Optional<StockView>> {

  private final StockItems stockItems;

  FindStockHandler(StockItems stockItems) {
    this.stockItems = stockItems;
  }

  @Override
  public Optional<StockView> handle(FindStock query) {
    return stockItems
        .findBySku(new Sku(query.sku()))
        .map(item -> new StockView(item.id().value(), item.available(), item.reserved()));
  }
}
