package com.example.samples.s24.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.NotBlank;

/** Read one order back. */
public record OrderQuery(@NotBlank String orderId) implements Query<OrderTotals> {}
