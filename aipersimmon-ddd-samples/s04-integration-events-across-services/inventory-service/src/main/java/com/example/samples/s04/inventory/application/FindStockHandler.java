package com.example.samples.s04.inventory.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s04.inventory.domain.Sku;
import com.example.samples.s04.inventory.domain.StockItems;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The read that lets a test see what the integration event did, behind the bus rather than in the
 * controller — which is where it used to be, holding the repository port directly.
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
