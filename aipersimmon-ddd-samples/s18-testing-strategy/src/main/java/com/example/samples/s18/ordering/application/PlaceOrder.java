package com.example.samples.s18.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Places an order and answers its id. */
public record PlaceOrder(@NotBlank String customerId, @Positive long amountCents)
    implements Command<String> {}
