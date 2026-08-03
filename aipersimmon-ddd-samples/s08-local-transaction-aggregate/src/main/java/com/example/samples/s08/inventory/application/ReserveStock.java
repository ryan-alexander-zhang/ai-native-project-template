package com.example.samples.s08.inventory.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** Reserves several skus at once — which is what makes the transaction boundary a real question. */
public record ReserveStock(@NotEmpty @Valid List<Line> lines) implements Command<Void> {

  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
