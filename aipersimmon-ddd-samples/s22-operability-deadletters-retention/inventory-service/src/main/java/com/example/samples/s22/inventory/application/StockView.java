package com.example.samples.s22.inventory.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** Stock for one SKU, as this service reports it. */
@ReadModel
public record StockView(String sku, int available, int reserved) {}
