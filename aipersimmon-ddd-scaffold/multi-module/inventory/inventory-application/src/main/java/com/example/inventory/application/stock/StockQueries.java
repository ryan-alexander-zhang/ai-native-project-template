package com.example.inventory.application.stock;

import java.util.List;

/**
 * The read port for stock levels: one question, one round trip.
 *
 * <p>A read port, not a repository. {@link com.example.inventory.domain.stock.Stocks} loads {@code
 * Stock} aggregates to be reserved and released, and going through it to answer a read means
 * rehydrating an aggregate per SKU that nobody is going to change — and doing it one query at a
 * time. That is the same distinction {@code OrderQueries} draws against {@code Orders} on the
 * ordering side, and the reason it lives in the application layer rather than the domain: the
 * aggregate boundary governs writes, and this is not one (issue-00084).
 *
 * <p>The one-query-per-SKU shape mattered because this read sits on the synchronous placement path:
 * {@code PlaceOrder} asks it, over the anti-corruption gateway, before the order is created. A
 * ten-line order paid ten round trips inside the request the customer is waiting on.
 */
public interface StockQueries {

  /**
   * The current availability of each requested SKU, in the order asked.
   *
   * <p>A SKU with no stock record reports {@code available = 0}, so an unknown SKU and an
   * out-of-stock one look identical to the caller. That is deliberate and part of the published
   * contract (see {@link StockLevel}) — it is all an offerability check needs, and it keeps the
   * exact on-hand level from leaking. A batch implementation must therefore fill in the SKUs its
   * {@code IN} clause found nothing for, rather than returning a shorter list.
   */
  List<StockLevel> levelsOf(List<String> skus);
}
