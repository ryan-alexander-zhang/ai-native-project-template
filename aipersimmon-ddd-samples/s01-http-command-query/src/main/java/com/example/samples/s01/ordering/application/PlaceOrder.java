package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Places an order and answers its id.
 *
 * <p>Constraints live here rather than only on the HTTP DTO, so that every entry point — HTTP now, a
 * message consumer or a scheduler later — gets the same check from the bus's validation interceptor.
 * {@code @Valid} on the collection is required for the nested constraints to be checked at all.
 */
public record PlaceOrder(
    @NotBlank String customerId,
    @NotEmpty @Valid List<Line> lines) implements Command<String> {

  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
