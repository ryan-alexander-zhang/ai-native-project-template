package com.example.samples.s22.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Place an order. */
public record PlaceOrder(
    @NotBlank String customerId, @NotBlank String sku, @Positive int quantity)
    implements Command<String> {}
