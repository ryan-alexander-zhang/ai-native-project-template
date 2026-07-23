package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/**
 * Ordering-internal command the saga sends to ask the inventory context to release a reservation
 * (the stock-release compensation). Its handler publishes the {@code StockReleaseRequested}
 * integration event carrying the {@code reservationId} inventory issued at reservation time. No
 * result.
 */
@OperationLog(
    code = "ordering.order.request-stock-release",
    targetType = "Order",
    targetId = "${input.orderId}",
    success =
        "Requested stock release for order ${input.orderId} (reservation ${input.reservationId})",
    failure =
        "Stock-release request for order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record RequestStockRelease(String orderId, String reservationId) implements Command<Void> {}
