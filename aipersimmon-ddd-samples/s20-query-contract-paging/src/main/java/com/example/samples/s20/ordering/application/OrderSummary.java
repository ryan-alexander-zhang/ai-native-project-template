package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.ReadModel;
import java.time.Instant;

/**
 * One row of the list. Shaped for the list, not for the aggregate — which is why it is flat, why it
 * carries no behaviour, and why {@code status} is a string rather than the domain enum: the wire
 * value must be free to outlive a rename inside the domain.
 *
 * <p>{@code placedAt} and {@code id} are here because they are the sort key. A read model that hides
 * its own ordering key cannot produce the cursor for the next page, so the pager would have to
 * re-read the row it just returned — the shape of the result and the shape of the cursor are one
 * decision, not two.
 *
 * <p>It is a {@code @ReadModel} without a {@code @Projection}: nothing maintains a separate store
 * here, the queries read the write tables directly. That is the right default until it stops being
 * one — see the README on when to escalate to S12.
 */
@ReadModel
public record OrderSummary(
    String id, String customerId, String status, int quantity, Instant placedAt) {}
