package com.example.samples.s04.inventory.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** What a caller gets back when it asks about a SKU's stock. */
@ReadModel
public record StockView(String sku, int available, int reserved) {}
