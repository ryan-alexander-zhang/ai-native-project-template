package com.example.inventory.api;

import java.util.List;

/**
 * Request DTO of {@link StockAvailabilityApi}: the SKUs whose current offerability the caller wants
 * to know. Part of the inventory context's published language — a flat, serialisable carrier,
 * independent of both the caller's model and inventory's internal types, so it survives the move
 * from an in-process call to an HTTP payload unchanged.
 */
public record StockQuery(List<String> skus) {

  public StockQuery {
    // Defensive copy so this published-language carrier stays immutable and cannot be
    // mutated through the caller's list reference after construction.
    skus = skus == null ? null : List.copyOf(skus);
  }
}
