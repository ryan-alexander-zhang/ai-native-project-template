package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/** Moves one order out of the default list's filter, mid-pagination, on purpose. */
public record ConfirmOrder(@NotBlank String orderId) implements Command<Void> {}
