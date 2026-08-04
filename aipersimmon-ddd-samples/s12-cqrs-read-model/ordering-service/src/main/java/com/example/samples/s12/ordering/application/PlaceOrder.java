package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** Place an order. */
public record PlaceOrder(@NotBlank String customerId, @NotEmpty @Valid List<Line> lines)
    implements Command<String> {

  public record Line(@NotBlank String sku, @Positive int quantity, @Positive long unitPriceMinor) {}
}
