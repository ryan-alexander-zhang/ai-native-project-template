package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * Confirms a placed order. {@code Command<Void>} means the handler returns {@code null}.
 *
 * <p>Audited by annotation (S14), and this command is the one that <em>can</em> be: the target's identity
 * — the order id — is in the input, so it is known before the handler runs and still known if the handler
 * throws. That is the whole difference from {@link PlaceOrder}, whose id does not exist until it succeeds;
 * see {@link PlaceOrderAudit}.
 *
 * <p>The templates are compiled at startup against a fixed set of roots, so a typo in a property path is a
 * failed boot rather than an audit row reading {@code "confirmed order "}. Note what is not available here:
 * {@code success} may read {@code input} and {@code resultProjection}, and {@code targetId} may read only
 * {@code input} — the annotation has no access to before-state and cannot record a {@code changes} list at
 * all. When an audit row has to say "from A to B", that is the Definition path's job.
 *
 * <p>{@code recordFailure = true} is the default and left explicit here because it is a compliance decision
 * rather than a detail: an audit trail that records only what worked cannot answer "did anyone try".
 */
@OperationLog(
    code = "ordering.order.confirm",
    targetType = "Order",
    targetId = "${input.orderId}",
    success = "Confirmed order ${input.orderId}",
    failure = "Could not confirm order ${input.orderId}: ${failure.code}",
    recordFailure = true)
public record ConfirmOrder(@NotBlank String orderId) implements Command<Void> {}
