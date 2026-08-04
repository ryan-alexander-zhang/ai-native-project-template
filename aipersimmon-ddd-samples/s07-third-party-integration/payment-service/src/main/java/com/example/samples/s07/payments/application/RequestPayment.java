package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Take this much money for this order. Returns the payment id. */
public record RequestPayment(@NotBlank String orderRef, @Positive long amountMinor)
    implements Command<String> {}
