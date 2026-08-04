package com.example.samples.s23.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Place an order. The destination arrives structured, because the current schema is structured. */
public record PlaceOrder(
    @NotBlank String customerId,
    @NotBlank String sku,
    @Positive int quantity,
    @NotBlank String street,
    @NotBlank String city)
    implements Command<String> {}
