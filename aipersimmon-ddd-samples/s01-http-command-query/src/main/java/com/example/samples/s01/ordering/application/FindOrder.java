package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.NotBlank;

/** Reads one order. Answered from the read side; no aggregate is loaded. */
public record FindOrder(@NotBlank String orderId) implements Query<OrderView> {}
