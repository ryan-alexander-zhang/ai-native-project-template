package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.page.Slice;
import jakarta.validation.constraints.NotNull;

/**
 * The default list: rows and a cursor, no totals. This is the shape to reach for first — the package
 * documentation in the library calls {@code Slice} "the primary shape ... matching the direction
 * large APIs have moved", and the reason is arithmetic, not fashion: a count is a scan of every
 * matching row, and almost no list screen needs one.
 */
public record BrowseOrders(@NotNull PageRequest page) implements Query<Slice<OrderSummary>> {}
