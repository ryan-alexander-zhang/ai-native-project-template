package com.example.samples.s19.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Layer two of the three: the command's own constraints.
 *
 * <p>They repeat what the HTTP body already declared, and that is correct rather than wasteful — this
 * is the contract every entry point shares, so a message consumer or a scheduler gets the same check
 * without the HTTP layer's help.
 */
public record PlaceOrder(@NotBlank String customerId, @Positive int quantity)
    implements Command<String> {}
