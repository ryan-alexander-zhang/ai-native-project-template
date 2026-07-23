package com.example.payment.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Command to authorize a payment for an order: the order id, the {@code paymentOperationId}
 * business idempotency key, and the amount to authorize in minor units with its currency. It
 * arrives from an integration-event listener, not from HTTP, so its Bean Validation constraints are
 * what the command bus enforces at this event-driven entry point. No result.
 *
 * <p>This reference context demonstrates the <em>authorization</em> step only (not a later
 * capture), so the whole flow speaks one word — authorize — end to end.
 *
 * <p>The {@code paymentOperationId} is the key the handler dedupes by: two commands carrying the
 * same operation id are one business authorization, so an at-least-once redelivery must not
 * authorize twice (design-00004 §13.2).
 */
@OperationLog(
    code = "payment.authorize",
    targetType = "Order",
    targetId = "${input.orderId}",
    success =
        "Authorized payment for order ${input.orderId} (${input.amountMinor} ${input.currency})",
    failure =
        "Authorizing payment for order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record AuthorizePayment(
    @NotBlank String orderId,
    @NotBlank String paymentOperationId,
    @Positive long amountMinor,
    @NotBlank String currency)
    implements Command<Void> {}
