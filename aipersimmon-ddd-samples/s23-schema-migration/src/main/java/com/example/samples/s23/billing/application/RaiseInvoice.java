package com.example.samples.s23.billing.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Raise an invoice for an order. */
public record RaiseInvoice(@NotBlank String orderId, @Positive long amountMinor)
    implements Command<String> {}
