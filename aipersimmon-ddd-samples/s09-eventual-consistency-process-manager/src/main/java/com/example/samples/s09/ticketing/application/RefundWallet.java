package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Flow effect, compensating: put the money back as a new ledger entry.
 *
 * <p>It carries {@code debitReference} — what the flow remembered from the charge — because a refund
 * names the movement it makes good. That is the difference between a compensation and a rollback in one
 * parameter: a rollback would have nothing to name.
 */
public record RefundWallet(
    @NotBlank String orderId,
    @NotBlank String customerId,
    @Positive long amountMinor,
    @NotBlank String debitReference,
    @NotBlank String reason)
    implements Command<Void> {}
