package com.example.samples.s21.inventory.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s21.inventory.domain.StockItems;
import com.example.samples.s21.inventory.domain.StockLocation;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Projects one stock item. Composing the {@code StockLocation} identity happens here rather than in
 * the endpoint, so the query's contract stays two strings and the domain type does not travel
 * outward.
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
        .find(new StockLocation(query.sku(), query.warehouse()))
        .map(
            item ->
                new StockView(
                    item.id().sku(), item.id().warehouse(), item.available(), item.reserved()));
  }
}
