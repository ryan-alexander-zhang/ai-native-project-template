package com.example.samples.s04.inventory.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for one SKU's stock. Empty when the SKU is unknown here. */
public record FindStock(String sku) implements Query<Optional<StockView>> {}
