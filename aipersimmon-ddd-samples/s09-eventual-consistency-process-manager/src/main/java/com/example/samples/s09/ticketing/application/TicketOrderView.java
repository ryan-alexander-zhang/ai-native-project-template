package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * A ticket order as a client sees it while the process manager works.
 *
 * <p>{@code status} and {@code cancelReason} together are the whole point of the answer: an
 * eventually-consistent flow is only observable if the caller can see which step it reached and, if
 * it unwound, why. {@code cancelReason} stays in the answer when null, so "not cancelled" and "the
 * field does not exist" never look the same to a poller.
 */
@ReadModel
public record TicketOrderView(
    String id,
    String customerId,
    String seatClass,
    long amountMinor,
    String status,
    String cancelReason) {}
