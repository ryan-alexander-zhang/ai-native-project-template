package com.example.inventory.application.stock;

import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * Read-side view of one SKU's current availability, returned by {@link
 * CheckStockAvailabilityHandler}. {@code available} is 0 when the SKU is not carried, so a caller
 * cannot tell "unknown SKU" from "out of stock" — which is all an offerability check needs, and
 * keeps the exact on-hand level from leaking.
 */
@ReadModel
public record StockLevel(String sku, int available) {}
