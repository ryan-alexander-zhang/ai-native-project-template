package com.example.samples.s02.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** What a read of one order answers. */
@ReadModel
public record OrderView(String id, String clientReference, long amountCents) {}
