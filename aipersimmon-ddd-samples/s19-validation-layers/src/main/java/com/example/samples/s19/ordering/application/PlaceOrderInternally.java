package com.example.samples.s19.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * The same order, placed by an internal path that has no prechecks registered against it — an
 * operator tool, a migration, a back-office correction.
 *
 * <p>It exists to make the point that prechecks are advisory: nothing about them is a guarantee, and
 * the aggregate's invariant is what actually holds when no one screened the command first.
 */
public record PlaceOrderInternally(@NotBlank String customerId, @Positive int quantity)
    implements Command<String> {}
