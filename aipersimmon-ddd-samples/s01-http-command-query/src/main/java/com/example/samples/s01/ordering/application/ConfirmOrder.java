package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Confirms a placed order. {@code Command<Void>} means the handler returns {@code null}. */
public record ConfirmOrder(@NotBlank String orderId) implements Command<Void> {}
