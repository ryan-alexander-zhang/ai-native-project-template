package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.ReadModel;
import io.swagger.v3.oas.annotations.media.Schema;

/** Read-side view of an order returned to callers, decoupled from the aggregate. */
@ReadModel
@Schema(description = "Read-side view of an order returned to callers.")
public record OrderSnapshot(
    @Schema(description = "Order identifier.", example = "ord-123") String id,
    @Schema(description = "Identifier of the customer who owns the order.", example = "cust-42")
        String customerId,
    @Schema(description = "Current order status.", example = "PLACED") String status,
    @Schema(
            description = "Order total in the currency's minor unit (e.g. cents/fen).",
            example = "3998")
        long totalMinor,
    @Schema(description = "ISO-4217 currency code.", example = "CNY") String currency) {}
