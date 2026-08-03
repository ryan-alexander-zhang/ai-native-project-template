package com.example.samples.s11.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The HTTP entry's command, here so the sweep has something to sweep. */
public record PlaceOrder(@NotBlank String customerId, @Positive int payWithinSeconds)
    implements Command<String> {}
