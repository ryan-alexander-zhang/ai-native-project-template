package com.example.samples.s03.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Places an order. {@code firstOrder} is what makes the welcome-coupon reaction interesting. */
public record PlaceOrder(
    @NotBlank String customerId, boolean firstOrder, @Positive long amountCents)
    implements Command<String> {}
