package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.example.ordering.domain.order.CancellationReason;

/**
 * Command to cancel an order (the fulfilment saga's compensation). It carries the evidence-bearing
 * {@link CancellationReason} the aggregate needs to authorise the cancellation — a bare order id
 * would let a caller assert "cancel" without saying why or proving that any compensation ran. No
 * result.
 */
@OperationLog(
    code = "ordering.order.cancel",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Cancelled order ${input.orderId} (reason ${input.reason})",
    failure = "Cancelling order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record CancelOrder(String orderId, CancellationReason reason) implements Command<Void> {}
