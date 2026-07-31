package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * Command to move a ready order into fulfilment, dispatched by the process manager once inventory
 * has actually reserved the stock. No result.
 *
 * <p>It exists because "the order is cleared for fulfilment" and "fulfilment has begun" are two
 * different facts, and the application used to record only the second. {@code FulfilmentTrigger}
 * transitioned the order to {@code FULFILMENT_IN_PROGRESS} in the same transaction that placed it,
 * so {@code READY_FOR_FULFILMENT} never reached a database row — and since that is the state the
 * customer's self-cancel window is defined over, the window was unreachable on the normal path
 * (issue-00070). Asking for a reservation is not the same as having one.
 */
@OperationLog(
    code = "ordering.order.begin-fulfilment",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Fulfilment began for order ${input.orderId}",
    failure = "Beginning fulfilment for order ${input.orderId} failed: ${failure.code}")
public record BeginFulfilment(@NotBlank String orderId) implements Command<Void> {}
