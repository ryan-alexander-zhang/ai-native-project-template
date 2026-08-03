package com.example.samples.s11.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** The race the sweep has to survive: a customer paying while a round is in flight. */
public record PayOrder(@NotBlank String orderId) implements Command<Void> {}
