package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Mark an order paid. */
public record PayOrder(@NotBlank String orderId) implements Command<Void> {}
