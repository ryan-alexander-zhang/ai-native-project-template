package com.example.samples.s21.inventory.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * Stock at one location, as this service reports it.
 *
 * <p>{@code warehouse} is in the answer because it is in the identity: this context keyed stock by
 * (sku, warehouse) when it learned to hold the same SKU in more than one place, and an answer that
 * reported only the SKU would be ambiguous the moment a second warehouse exists.
 */
@ReadModel
public record StockView(String sku, String warehouse, int available, int reserved) {}
