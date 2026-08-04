package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Flow effect: take the ticket price out of the customer's balance. */
public record ChargeWallet(
    @NotBlank String orderId, @NotBlank String customerId, @Positive long amountMinor)
    implements Command<Void> {}
