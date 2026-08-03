package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * Command to dispatch a confirmed order. An operator action, like {@link ApproveReview}: the
 * warehouse says the goods have left, and the order reaches its terminal successful state.
 *
 * <p>Without it {@code SHIPPED} was a state no running application could ever hold, which quietly
 * disabled a rule the domain had gone to some trouble to express — {@code OrderLifecyclePolicy}
 * refuses to cancel a shipped order with {@code RETURN_REQUIRED}, because undoing a dispatch is a
 * return, not a cancellation. A good rule that can never fire is indistinguishable from no rule,
 * and the aggregate's test suite was the only thing that could tell.
 *
 * <p>What this scaffold still does not demonstrate is the return flow that {@code RETURN_REQUIRED}
 * points at; shipping is where the modelled lifecycle ends. No result.
 */
@OperationLog(
    code = "ordering.order.ship",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Shipped order ${input.orderId}",
    failure = "Shipping order ${input.orderId} failed: ${failure.code} (${failure.safeSummary})")
public record ShipOrder(@NotBlank String orderId) implements Command<Void> {}
