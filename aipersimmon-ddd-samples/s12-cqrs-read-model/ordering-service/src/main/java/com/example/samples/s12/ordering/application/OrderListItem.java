package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;
import java.time.Instant;

/**
 * One row of the order list page — the shape the screen wants, and nothing else.
 *
 * <p>Flat, pre-joined, with no behaviour and no identity object. That is what makes it a read model rather
 * than a DTO over an aggregate: it is not a view of an {@code Order}, it is a different thing built for a
 * different question, and it contains data ({@link #displaySummary}) that no {@code Order} has.
 *
 * @param displaySummary the product names as they are called <em>now</em>. The order's own lines hold the
 *     names as of purchase; this is the other requirement, and having both is the reason the projection
 *     exists at all.
 * @param projectedAt when this row was last recomputed. Carried out to the caller on purpose: a read model
 *     that cannot tell you how old it is forces every consumer to guess.
 */
@ReadModel
public record OrderListItem(
    String orderId,
    String customerId,
    String status,
    Instant placedAt,
    Instant paidAt,
    int lineCount,
    long totalMinor,
    String displaySummary,
    Instant projectedAt) {}
