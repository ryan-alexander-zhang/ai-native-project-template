package com.example.samples.s21.inventory.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for stock at one location. Empty when this warehouse holds no such SKU. */
public record FindStock(String warehouse, String sku) implements Query<Optional<StockView>> {}
