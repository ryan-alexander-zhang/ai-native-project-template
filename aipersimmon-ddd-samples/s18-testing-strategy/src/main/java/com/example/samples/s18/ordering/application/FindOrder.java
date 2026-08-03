package com.example.samples.s18.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.NotBlank;

/** Reads one order. */
public record FindOrder(@NotBlank String orderId) implements Query<OrderView> {}
