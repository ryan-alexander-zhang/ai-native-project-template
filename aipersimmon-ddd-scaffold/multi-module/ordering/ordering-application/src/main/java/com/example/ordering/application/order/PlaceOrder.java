package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * Command to place an order: the customer and the lines, in primitive input form.
 * Dispatched through the command bus; its result is the new order id.
 *
 * <p>This is the reference example of Bean Validation (JSR 380) on a command. The
 * command bus's validation interceptor checks these constraints before the handler
 * runs and before any transaction opens, rejecting a malformed command with a 400 —
 * so this guards every entry into the application, not just the web adapter. Note
 * {@code @Valid} on the list element, which cascades into each {@link Line}.
 */
public record PlaceOrder(
        @NotBlank String customerId,
        @NotEmpty List<@Valid Line> lines) implements Command<String> {

    public record Line(
            @NotBlank String sku,
            @Positive int quantity,
            @PositiveOrZero long unitAmountMinor,
            @NotBlank String currency) {
    }
}
