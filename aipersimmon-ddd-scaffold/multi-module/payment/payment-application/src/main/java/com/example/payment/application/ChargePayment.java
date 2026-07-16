package com.example.payment.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Command to charge for an order: the order id and the amount to charge in minor units with its
 * currency. It arrives from an integration-event listener, not from HTTP, so its Bean Validation
 * constraints are what the command bus enforces at this event-driven entry point. No result.
 */
public record ChargePayment(
        @NotBlank String orderId,
        @Positive long amountMinor,
        @NotBlank String currency) implements Command<Void> {
}
