package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.shared.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Command: place an order. Task-based intent, returns the new order id
 * (analysis-00005 §5.1). Distinct from the web {@code PlaceOrderRequest} DTO —
 * the adapter maps request → command. Bean Validation constraints are enforced by
 * the CommandBus's Validation decorator before the transaction opens.
 */
public record PlaceOrderCommand(
        @NotBlank String customerId,
        @NotEmpty List<@Valid Line> lines) implements Command<String> {

    public record Line(@NotBlank String sku, @Positive int qty) {}
}
