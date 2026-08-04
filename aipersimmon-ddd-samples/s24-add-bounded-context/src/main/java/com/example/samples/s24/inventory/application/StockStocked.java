package com.example.samples.s24.inventory.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Put stock on the shelf, so tests and a running service have something to reserve. */
public record StockStocked(@NotBlank String sku, @Min(0) int onHand) implements Command<Void> {}
