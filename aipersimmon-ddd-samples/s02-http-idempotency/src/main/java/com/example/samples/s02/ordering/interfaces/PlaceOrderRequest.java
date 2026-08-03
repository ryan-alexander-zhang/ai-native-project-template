package com.example.samples.s02.ordering.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The HTTP request body. */
record PlaceOrderRequest(@NotBlank String clientReference, @Positive long amountCents) {}
