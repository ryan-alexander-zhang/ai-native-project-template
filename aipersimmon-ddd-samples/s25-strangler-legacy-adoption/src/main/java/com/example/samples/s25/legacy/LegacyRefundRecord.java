package com.example.samples.s25.legacy;

/** A refund as the monolith describes it. Also a legacy type; also must not leave this package. */
public record LegacyRefundRecord(
    long id, long orderId, long amountCents, String reason, String state) {}
