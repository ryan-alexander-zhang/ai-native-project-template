package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.time.Instant;
import java.util.List;

/**
 * Integration event published when an order is cleared for fulfilment — the ordering context's
 * cross-context contract that asks inventory to reserve stock. It is announced when the order
 * becomes ready (immediately at placement for an order needing no review, or later when review is
 * approved), <em>not</em> merely when the order is created: an order held for manual review
 * reserves nothing until it clears. It carries the ids and quantities inventory needs, never the
 * internal domain model.
 *
 * <h2>This is the scaffold's worked example of evolving a published event</h2>
 *
 * <p><strong>Version 2.</strong> {@link OrderReadyForFulfilmentV1} is the previous revision,
 * retained and still understood by consumers. That pair is the point: schema evolution of a
 * published contract is the hardest part of integrating bounded contexts, and every other event
 * here is at {@code version = 1}, so nothing demonstrated it.
 *
 * <p>The mechanics, all of which the library already supports:
 *
 * <ul>
 *   <li>The catalog keys classes by {@code (name, version)}, so both revisions register under the
 *       <em>same logical name</em> and coexist. Two classes claiming the same pair fail startup — a
 *       contract clash is loud, not silent.
 *   <li>Both are {@code @Externalized} to the same topic. A version bump is not a new topic:
 *       consumers of one order's events must keep seeing them in order, and splitting the topic
 *       would break that for exactly the window the migration is trying to survive.
 *   <li>Only this revision is ever <em>published</em> — {@code FulfilmentTrigger} constructs
 *       nothing else. V1 exists to be <em>read</em>.
 * </ul>
 *
 * <p><strong>Why the old class cannot simply be deleted.</strong> At the moment a deployment rolls
 * from v1 to v2, the topic still holds v1 messages that were published minutes earlier, and the
 * inbox may still redeliver older ones after that. A consumer that only knew v2 would fail to
 * resolve them and dead-letter a backlog of perfectly valid work. The old revision is retired when
 * the topic and the retry window have drained — a decision about elapsed time and retention, not
 * about code.
 *
 * <p><strong>Why this change is additive, and what to do when yours is not.</strong> {@code
 * reservationDeadline} is a new optional-in-practice field; the line shape is untouched. That is
 * the cheap case, and it is cheap precisely because a consumer can supply a stand-in for what is
 * missing. A <em>breaking</em> change — renaming a field, changing {@code quantity}'s unit,
 * splitting {@code lines} — cannot be absorbed that way and needs the same two-revision overlap
 * plus a real translation in the consumer's anticorruption layer. The overlap mechanism is
 * identical; only the upcast gets harder. See {@code OrderReadyForFulfilmentListener} in {@code
 * inventory-adapter} for where that translation belongs.
 *
 * @param reservationDeadline when ordering will stop waiting for an answer and compensate — the
 *     instant its own STOCK deadline fires. Ordering is the only context that knows this (it owns
 *     the timer and its configured budget), and it is not recoverable from envelope metadata, which
 *     is what makes it worth adding to the payload rather than deriving downstream.
 *     <p>Inventory does not act on it yet, and that is a consumer's choice rather than an omission:
 *     publishing a fact is not the same as every consumer using it. A consumer that wanted to could
 *     decline to hold stock for a request that has already expired.
 */
@EventType(name = "com.example.ordering.OrderReadyForFulfilment", version = 2, source = "/ordering")
@Externalized("ordering.events")
public record OrderReadyForFulfilment(String orderId, List<Line> lines, Instant reservationDeadline)
    implements IntegrationEvent {

  public OrderReadyForFulfilment {
    // Defensive copy so this published event stays immutable and cannot be mutated
    // through the caller's list reference after construction.
    lines = lines == null ? null : List.copyOf(lines);
  }

  @Override
  public String subject() {
    return orderId();
  }

  public record Line(String sku, int quantity) {}
}
