package com.example.samples.s06.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Place an order. Nothing in it mentions risk: the check is a precondition, not an input. */
public record PlaceOrder(@NotBlank String customerId, @Positive long amountCents)
    implements Command<String> {}
