package com.example.payment.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * Command to void a payment operation — the payment context's side of ordering's payment
 * compensation. Sent by the {@code PaymentVoidRequestedListener} when ordering's flow abandoned its
 * wait for this operation. Keyed by the same {@code paymentOperationId} the authorization is, which
 * is what lets the operation row settle the race between the two. No result, and no outcome event:
 * nothing waits on a void.
 */
@OperationLog(
    code = "payment.operation.void",
    targetType = "PaymentOperation",
    targetId = "${input.paymentOperationId}",
    success = "Voided payment operation ${input.paymentOperationId} (order ${input.orderId})",
    failure =
        "Voiding payment operation ${input.paymentOperationId} failed: ${failure.code}"
            + " (${failure.safeSummary})")
public record VoidPayment(@NotBlank String orderId, @NotBlank String paymentOperationId)
    implements Command<Void> {}
