package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.ReadModel;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of a customer's order list. Deliberately smaller than {@link OrderSnapshot}: a list shows
 * what a customer scans by, and the lines behind each total are not part of that.
 *
 * <p>It is assembled by one SQL statement over the order tables, never by loading aggregates. A
 * list of fifty orders would otherwise mean fifty rehydrations — each rebuilding lines and
 * invariants nobody is going to use, since a read changes nothing.
 */
@ReadModel
@Schema(description = "One order in a customer's order list.")
public record OrderListItem(
    @Schema(description = "Order identifier.", example = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f")
        String id,
    @Schema(description = "Current order status.", example = "CONFIRMED") String status,
    @Schema(description = "Order total in the currency's minor unit.", example = "3998")
        long totalMinor,
    @Schema(description = "ISO-4217 currency code.", example = "USD") String currency) {}
