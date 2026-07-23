package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/**
 * Command to approve the manual review of an order held in {@code AWAITING_REVIEW}, clearing it for
 * fulfilment. Unlike {@code ConfirmOrder} (a saga-internal step), this is a legitimate operator
 * action with its own public entry point: it only <em>starts</em> fulfilment, it does not assert
 * that stock and payment already succeeded. No result.
 */
@OperationLog(
    code = "ordering.order.approve-review",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Approved review for order ${input.orderId}",
    failure =
        "Approving review for order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record ApproveReview(String orderId) implements Command<Void> {}
