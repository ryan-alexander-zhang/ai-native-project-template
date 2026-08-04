package com.example.samples.s24.inventory.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Set some stock aside. */
public record ReserveStock(@NotBlank String sku, @Min(1) int quantity) implements Command<Boolean> {}
