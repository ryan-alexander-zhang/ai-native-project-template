package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.ReadModel;
import io.swagger.v3.oas.annotations.media.Schema;

/** Read-side view of an order returned to callers, decoupled from the aggregate. */
@ReadModel
@Schema(description = "Read-side view of an order returned to callers.")
public record OrderSnapshot(
    @Schema(description = "Order identifier.", example = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f")
        String id,
    @Schema(description = "Identifier of the customer who owns the order.", example = "CUST-1")
        String customerId,
    // allowableValues rather than a hand-written example: the status set is an enum, so letting the
    // schema name its members is what keeps the published contract from drifting when the state
    // machine changes. A literal example here once advertised PLACED, a state this model does not
    // have — see issue-00081.
    @Schema(
            description = "Current order status.",
            example = "FULFILMENT_IN_PROGRESS",
            allowableValues = {
              "AWAITING_REVIEW",
              "READY_FOR_FULFILMENT",
              "FULFILMENT_IN_PROGRESS",
              "CONFIRMED",
              "SHIPPED",
              "CANCELLED"
            })
        String status,
    @Schema(
            description = "Order total in the currency's minor unit (e.g. cents/fen).",
            example = "3998")
        long totalMinor,
    @Schema(description = "ISO-4217 currency code.", example = "USD") String currency,
    @Schema(
            description =
                "Whether the owning customer may still cancel this order themselves. Answered by"
                    + " the CancellableByCustomer specification, so a client can decide whether to"
                    + " offer the action instead of attempting it and reading the error.",
            example = "true")
        boolean cancellableByCustomer) {}
