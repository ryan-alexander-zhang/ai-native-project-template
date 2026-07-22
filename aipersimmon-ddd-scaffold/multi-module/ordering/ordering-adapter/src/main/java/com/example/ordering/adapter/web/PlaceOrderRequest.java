package com.example.ordering.adapter.web;

import com.example.ordering.application.order.PlaceOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * Web request body for placing an order; maps to the {@link PlaceOrder} command.
 *
 * <p>Edge-tier validation: these constraints are checked by Spring MVC when the controller
 * parameter is {@code @Valid}, giving a 400 at the HTTP boundary before any command is dispatched.
 * The command itself carries an equivalent set of constraints, so non-web entry points are guarded
 * too (see {@link PlaceOrder}).
 */
public record PlaceOrderRequest(
    @Schema(description = "Identifier of the customer placing the order.", example = "cust-42")
        @NotBlank
        String customerId,
    @Schema(description = "Order lines; at least one is required.") @NotEmpty
        List<@Valid Line> lines) {

  public PlaceOrderRequest {
    // Defensive copy: keep this immutable request isolated from later mutation of the
    // caller's list. Null is left as-is so @NotEmpty still reports it as a validation error.
    lines = lines == null ? null : List.copyOf(lines);
  }

  public record Line(
      @Schema(description = "Stock-keeping unit of the item.", example = "SKU-1001") @NotBlank
          String sku,
      @Schema(description = "Quantity ordered; must be positive.", example = "2") @Positive
          int quantity,
      @Schema(
              description = "Unit price in the currency's minor unit (e.g. cents/fen).",
              example = "1999")
          @PositiveOrZero
          long unitAmountMinor,
      @Schema(description = "ISO-4217 currency code.", example = "CNY") @NotBlank
          String currency) {}

  public PlaceOrder toCommand() {
    List<PlaceOrder.Line> commandLines =
        lines.stream()
            .map(
                line ->
                    new PlaceOrder.Line(
                        line.sku(), line.quantity(), line.unitAmountMinor(), line.currency()))
            .toList();
    return new PlaceOrder(customerId, commandLines);
  }
}
