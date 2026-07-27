package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * A customer cancelling their own order.
 *
 * <p>Distinct from {@link CancelOrder}, which the fulfilment process manager dispatches with
 * evidence of a failure. This one is an intent, not a compensation: the only thing that makes it
 * legitimate is who is asking and how far the order has got, which is what {@code
 * CancellableByCustomer} judges.
 *
 * @param orderId the order to cancel
 * @param customerId who is asking; the trusted edge supplies this in a real deployment
 */
@OperationLog(
    code = "ordering.order.cancel-own",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Customer ${input.customerId} cancelled order ${input.orderId}",
    failure = "Customer cancellation of ${input.orderId} refused: ${failure.code}")
public record CancelOwnOrder(@NotBlank String orderId, @NotBlank String customerId)
    implements Command<Void> {}
