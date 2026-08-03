package com.example.samples.s11.ordering.infrastructure;

import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * A deliberate counterexample, kept because a test proves what it loses.
 *
 * <p>One statement closes every overdue order. It is faster than a thousand commands and it is the
 * first thing most people write, so it is worth being precise about what it costs:
 *
 * <ul>
 *   <li><strong>The rule is gone.</strong> {@code WHERE payment_due_at < now} does not know that an
 *       order was paid. It closes paid orders, and the transition table that would have refused is
 *       never consulted.
 *   <li><strong>The events are gone.</strong> Nothing is published, so every reaction that should
 *       follow a closure does not happen — for exactly the rows the batch touched, and only for
 *       those, which is the hardest kind of inconsistency to notice.
 *   <li><strong>The optimistic lock is gone.</strong> {@code version = version + 1} without a {@code
 *       WHERE version = ?} predicate is not a concurrency control; it is a counter.
 *   <li><strong>Failure is all-or-nothing.</strong> The whole backlog is one statement in one
 *       transaction. One bad row, and nothing was closed.
 *   <li><strong>Unbounded.</strong> It takes as many rows as it finds, so the round after an outage
 *       is the one that locks the table.
 * </ul>
 *
 * <p>None of which means "never write a bulk statement". It means a bulk statement is a data-fix
 * tool, not an entry point for a business action — and if a business action genuinely must be
 * expressed in one statement for volume reasons, the rule and the events it skips have to be
 * accounted for somewhere else, on purpose, in writing.
 */
@Component
public class BulkCloser {

  private final OrderMapper mapper;

  BulkCloser(OrderMapper mapper) {
    this.mapper = mapper;
  }

  /** @return rows updated, straight from the statement */
  public int closeEverythingOverdue(Instant asOf) {
    return mapper.closeEverythingOverdue(asOf.toString());
  }
}
