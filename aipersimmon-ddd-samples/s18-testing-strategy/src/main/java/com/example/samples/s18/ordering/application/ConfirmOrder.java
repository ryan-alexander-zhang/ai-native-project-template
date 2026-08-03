package com.example.samples.s18.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Confirms a placed order. */
public record ConfirmOrder(@NotBlank String orderId) implements Command<Void> {}
