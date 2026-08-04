package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * A message from another system, absorbed at most once.
 *
 * <p>Stands in for a real consumer — no broker, because what this sample needs from the inbox is not delivery
 * but the <em>keys</em>: the erasure has to decide whether they are personal data, and whether removing them
 * is safe. Driving the port directly keeps that question in view without dragging S5's subject in.
 *
 * @param source the producing system's identity, which scopes the key
 * @param messageKey the producer's message id, unique only within {@code source}
 */
public record AbsorbMarketingSignal(
    @NotBlank String source, @NotBlank String messageKey, @NotBlank String customerId, String note)
    implements Command<Boolean> {}
