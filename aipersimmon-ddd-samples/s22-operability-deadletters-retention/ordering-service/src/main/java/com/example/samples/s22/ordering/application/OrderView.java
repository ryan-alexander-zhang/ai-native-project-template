package com.example.samples.s22.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** An order, as this service reports it. */
@ReadModel
public record OrderView(String id, String customerId, String sku, int quantity) {}
