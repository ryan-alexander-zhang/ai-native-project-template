package com.example.payment.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

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
 * authorize twice.
 *
 * <p>{@code amountMinor} is {@code @PositiveOrZero}, not {@code @Positive}, and that has to stay
 * reconciled with the range ordering accepts. A violation at this entry point is not a 400 handed
 * back to a caller — the command arrives from an event listener, so a rejected command is a
 * poisoned message that retries until it dead-letters, while the ordering flow sits in {@code
 * AWAITING_PAYMENT} until its deadline cancels the order as {@code PAYMENT_TIMEOUT}. The customer
 * sees a successful order quietly cancelled two minutes later for a reason unrelated to the truth.
 * Zero is therefore in range here because it is in range there; ordering's {@code PaymentRequested}
 * is where the two sides write that agreement down.
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
    @PositiveOrZero long amountMinor,
    @NotBlank String currency)
    implements Command<Void> {}
