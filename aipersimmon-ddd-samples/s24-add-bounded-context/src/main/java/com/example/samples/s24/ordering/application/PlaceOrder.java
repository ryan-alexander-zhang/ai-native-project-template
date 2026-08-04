package com.example.samples.s24.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Place an order, optionally quoting a coupon code.
 *
 * <p>The coupon code is a plain {@code String} here, not a {@code CouponCode}. A command is a message from the edge and
 * its fields are whatever arrived; parsing it into the other context's type happens in the handler, where a bad value
 * becomes a 400 rather than an exception thrown while binding a request body.
 */
public record PlaceOrder(
    @NotBlank String orderId,
    @NotBlank String customerId,
    @NotBlank String currency,
    String couponCode,
    @NotEmpty List<@Valid Line> lines)
    implements Command<OrderTotals> {

  public record Line(@NotBlank String sku, @Min(1) int quantity, @Min(0) long unitMinor) {}
}
