package com.example.samples.s18.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** What a read of one order answers. */
@ReadModel
public record OrderView(String id, String customerId, long amountCents, String status) {}
