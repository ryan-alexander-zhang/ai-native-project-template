package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Answers {@link CheckStockAvailability} from the {@link StockQueries} read port: one query for
 * however many SKUs were asked about. A SKU with no stock record reports {@code available = 0}, so
 * an unknown SKU and an out-of-stock one look the same to the caller — enough for an offerability
 * check. Read-only: it neither reserves nor mutates stock, which is why it is a query handler with
 * no {@code CommandContext} and no events.
 *
 * <p>It used to walk the {@code Stocks} repository one SKU at a time, rehydrating a {@code Stock}
 * aggregate per line to read a single {@code int} off it and discard the rest. Since this read sits
 * on the synchronous placement path — {@code PlaceOrder} consults it before creating the order — a
 * ten-line order paid ten round trips inside the request the customer was waiting on, and the cost
 * grew with the order (issue-00084). Reads go through a read port for the same reason the order
 * list does.
 */
@Component
public class CheckStockAvailabilityHandler
    implements QueryHandler<CheckStockAvailability, List<StockLevel>> {

  private final StockQueries stockLevels;

  public CheckStockAvailabilityHandler(StockQueries stockLevels) {
    this.stockLevels = stockLevels;
  }

  @Override
  public List<StockLevel> handle(CheckStockAvailability query) {
    return stockLevels.levelsOf(query.skus());
  }
}
