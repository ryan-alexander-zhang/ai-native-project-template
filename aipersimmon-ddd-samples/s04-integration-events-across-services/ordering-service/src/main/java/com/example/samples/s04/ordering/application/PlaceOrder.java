package com.example.samples.s04.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Place an order. {@code draftOnly} chooses which of the two events the handler publishes. */
public record PlaceOrder(
    @NotBlank String customerId, @NotEmpty List<Line> lines, boolean draftOnly)
    implements Command<String> {

  public record Line(@NotBlank String sku, int quantity) {}
}
