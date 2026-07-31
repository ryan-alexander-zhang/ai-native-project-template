package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;

/**
 * Ordering-internal command the process manager sends when it abandons its wait for a payment
 * answer — a timeout, or a customer cancellation racing the authorization (issue-00144). Its
 * handler publishes the {@code PaymentVoidRequested} integration event; the payment context then
 * settles the race on its own operation row: an authorization already granted is voided, one still
 * in flight is refused, one already declined needs nothing.
 *
 * <p>It carries the same {@code paymentOperationId} the earlier {@code RequestPayment} was minted
 * with — remembered in the flow's state, because by the time the flow gives up, the causing
 * envelope is a timer or a cancellation and the operation id is not derivable from it. No result.
 */
@OperationLog(
    code = "ordering.order.request-payment-void",
    targetType = "Order",
    targetId = "${input.orderId}",
    success =
        "Requested payment void for order ${input.orderId} (operation ${input.paymentOperationId})",
    failure =
        "Payment-void request for order ${input.orderId} failed: ${failure.code}"
            + " (${failure.safeSummary})")
public record RequestPaymentVoid(String orderId, String paymentOperationId)
    implements Command<Void> {}
