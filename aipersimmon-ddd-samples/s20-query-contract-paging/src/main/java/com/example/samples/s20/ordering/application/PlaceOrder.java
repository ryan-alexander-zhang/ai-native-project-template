package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The write side exists here to make rows worth listing. */
public record PlaceOrder(@NotBlank String customerId, @Positive int quantity)
    implements Command<String> {}
