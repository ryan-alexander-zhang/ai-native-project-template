package com.example.samples.s25.refunds.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Min;

/**
 * Raise a refund.
 *
 * <p>The signature is the legacy method's arguments, deliberately: {@code (orderId, amountCents, reason)}. An
 * extraction that took the opportunity to improve the parameters would have made the delegation in
 * {@code LegacyRefundEntryPoint} a translation rather than a call, and a translation is a place for a bug to live
 * during exactly the period when both paths have to agree.
 *
 * @return the reserved id, because the legacy method returned one and its callers use it
 */
public record RaiseRefund(@Min(1) long orderId, @Min(1) long amountCents, String reason)
    implements Command<Long> {}
