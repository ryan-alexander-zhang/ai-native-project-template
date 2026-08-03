package com.example.samples.s01.ordering.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * The HTTP request body — a separate type from the command even though the fields currently match.
 * This one belongs to the HTTP contract: it may grow a deprecated field to keep an old client
 * working, and that should not reach the application layer.
 */
record PlaceOrderRequest(
    @NotBlank String customerId,
    @NotEmpty @Valid List<Line> lines) {

  record Line(@NotBlank String sku, @Positive int quantity) {}
}
