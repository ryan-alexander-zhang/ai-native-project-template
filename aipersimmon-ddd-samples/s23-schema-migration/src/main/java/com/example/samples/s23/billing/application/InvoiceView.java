package com.example.samples.s23.billing.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/** An invoice, as this context reports it. */
@ReadModel
public record InvoiceView(String id, String orderId, long amountMinor) {}
