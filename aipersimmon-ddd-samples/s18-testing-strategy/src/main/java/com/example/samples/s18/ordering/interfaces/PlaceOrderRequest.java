package com.example.samples.s18.ordering.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The HTTP request body. */
record PlaceOrderRequest(@NotBlank String customerId, @Positive long amountCents) {}
