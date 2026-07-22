package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stocks;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Answers {@link CheckStockAvailability} by reading the current level of each SKU from the {@link
 * Stocks} repository. A SKU with no stock record reports {@code available = 0}, so an unknown SKU
 * and an out-of-stock one look the same to the caller — enough for an offerability check.
 * Read-only: it neither reserves nor mutates stock, which is why it is a query handler with no
 * {@code CommandContext} and no events.
 */
@Component
public class CheckStockAvailabilityHandler
    implements QueryHandler<CheckStockAvailability, List<StockLevel>> {

  private final Stocks stocks;

  public CheckStockAvailabilityHandler(Stocks stocks) {
    this.stocks = stocks;
  }

  @Override
  public List<StockLevel> handle(CheckStockAvailability query) {
    return query.skus().stream()
        .map(
            sku ->
                new StockLevel(
                    sku, stocks.findBySku(new Sku(sku)).map(stock -> stock.available()).orElse(0)))
        .toList();
  }
}
