package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.ReadModel;
import com.example.ordering.domain.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Read-side view of an order returned to callers, decoupled from the aggregate.
 *
 * <p><strong>Trade-off — {@code @Schema} in the application layer.</strong> These are OpenAPI
 * annotations, transport documentation, and this module otherwise keeps transport out. The clean
 * alternative is an adapter-layer response DTO that exists only to carry the annotations, plus a
 * field-for-field mapping this record would have to stay in step with. That pure-forwarding layer
 * was judged a worse deal than the leak: {@code swagger-annotations-jakarta} is annotations only
 * (no runtime behaviour, no framework), and the read model already IS the wire shape by design. The
 * line drawn: annotation metadata may ride on a read model; anything with behaviour — serializers,
 * validators bound to HTTP semantics, servlet types — still may not. A context whose read models
 * serve several transports should reintroduce the DTO instead.
 */
@ReadModel
@Schema(description = "Read-side view of an order returned to callers.")
public record OrderSnapshot(
    @Schema(description = "Order identifier.", example = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f")
        String id,
    @Schema(description = "Identifier of the customer who owns the order.", example = "CUST-1")
        String customerId,
    // Typed as the enum, so springdoc DERIVES the value list from OrderStatus instead of repeating
    // it. The wire format is unchanged — Jackson writes an enum as its name — but the contract can
    // no longer drift from the state machine, because there is nothing left to keep in step.
    //
    // It was a String with a hand-written allowableValues list, which is a duplicate of the enum
    // maintained by memory, and it had already rotted once: a literal example advertised PLACED, a
    // state this model does not have (issue-00081). Fixing that instance left the mechanism that
    // produced it in place — adding a state still meant remembering to edit an annotation. Now
    // adding a state to OrderStatus updates the published schema on the next build.
    @Schema(description = "Current order status.", example = "FULFILMENT_IN_PROGRESS")
        OrderStatus status,
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
