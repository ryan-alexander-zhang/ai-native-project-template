package com.example.howto;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * Command to place an order; its result is the order id. The {@code @NotBlank}
 * constraint is enforced by the validation interceptor before the handler runs.
 */
public record PlaceOrder(@NotBlank String orderId, @NotBlank String sku) implements Command<String> {
}
