package com.example.payment.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Command to charge for an order: the order id, the {@code paymentOperationId} business idempotency
 * key, and the amount to charge in minor units with its currency. It arrives from an
 * integration-event listener, not from HTTP, so its Bean Validation constraints are what the
 * command bus enforces at this event-driven entry point. No result.
 *
 * <p>The {@code paymentOperationId} is the key the handler dedupes by: two commands carrying the
 * same operation id are one business charge, so an at-least-once redelivery must not charge twice
 * (design-00004 §13.2).
 */
public record ChargePayment(
    @NotBlank String orderId,
    @NotBlank String paymentOperationId,
    @Positive long amountMinor,
    @NotBlank String currency)
    implements Command<Void> {}
