package com.example.ordering.adapter.web;

import com.example.ordering.application.order.PlaceOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * Web request body for placing an order; maps to the {@link PlaceOrder} command.
 *
 * <p>Edge-tier validation: these constraints are checked by Spring MVC when the
 * controller parameter is {@code @Valid}, giving a 400 at the HTTP boundary before
 * any command is dispatched. The command itself carries an equivalent set of
 * constraints, so non-web entry points are guarded too (see {@link PlaceOrder}).
 */
public record PlaceOrderRequest(
        @NotBlank String customerId,
        @NotEmpty List<@Valid Line> lines) {

    public record Line(
            @NotBlank String sku,
            @Positive int quantity,
            @PositiveOrZero long unitAmountMinor,
            @NotBlank String currency) {
    }

    public PlaceOrder toCommand() {
        List<PlaceOrder.Line> commandLines = lines.stream()
                .map(line -> new PlaceOrder.Line(
                        line.sku(), line.quantity(), line.unitAmountMinor(), line.currency()))
                .toList();
        return new PlaceOrder(customerId, commandLines);
    }
}
