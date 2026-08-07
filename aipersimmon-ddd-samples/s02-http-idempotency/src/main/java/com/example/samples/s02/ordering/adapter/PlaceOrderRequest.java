package com.example.samples.s02.ordering.adapter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** The HTTP request body. */
record PlaceOrderRequest(@NotBlank String clientReference, @Positive long amountCents) {}
