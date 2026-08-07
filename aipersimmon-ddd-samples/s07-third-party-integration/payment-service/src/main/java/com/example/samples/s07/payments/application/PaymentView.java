package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * A payment, as this service reports it to whoever asked for one.
 *
 * <p>{@code gatewayRef} and {@code reviewReason} are nullable on purpose and stay in the answer when
 * null: "no gateway reference yet" and "not under review" are facts a poller needs, and an answer
 * that omitted the fields would make them indistinguishable from a response shape that never had
 * them.
 *
 * <p>Nothing here is the provider's vocabulary. {@code status} is this context's own enum name, not
 * a {@code result_code} — the anticorruption layer has already done that translation, and letting
 * the provider's spelling reach this record would undo it.
 */
@ReadModel
public record PaymentView(
    String id,
    String orderRef,
    long amountMinor,
    String status,
    String gatewayRef,
    boolean needsReview,
    String reviewReason) {}
