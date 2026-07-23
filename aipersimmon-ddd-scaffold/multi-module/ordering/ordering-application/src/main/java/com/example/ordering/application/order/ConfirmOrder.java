package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/** Command to confirm an order (sent by the fulfilment saga on reservation). No result. */
@OperationLog(
    code = "ordering.order.confirm",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Confirmed order ${input.orderId}",
    failure = "Confirming order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record ConfirmOrder(String orderId) implements Command<Void> {}
