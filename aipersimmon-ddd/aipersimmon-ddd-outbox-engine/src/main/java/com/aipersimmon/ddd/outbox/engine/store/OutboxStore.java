package com.aipersimmon.ddd.outbox.engine.store;

import java.time.Instant;
import java.util.List;

/**
 * The one port the outbox engine runs on: every row-level operation the writer, relay and cleanup
 * need, and nothing else.
 *
 * <p>Deliberately narrow. Everything that is a <em>decision</em> — which rows are due, in what
 * order, what a failure means, when to give up, how long to back off — lives in the engine, so both
 * storage backends make the same decisions by construction. What is left here is only "how do I
 * read and write this table", which is the one thing a backend genuinely knows better.
 *
 * <p>The due-work query is the exception that proves the rule: its predicate is not "how to read a
 * table" but the per-aggregate ordering guarantee, so {@link #findDue} carries that contract in its
 * javadoc and an implementation must honour it rather than invent one. It is expressed as SQL twice
 * because the two backends speak different query languages, and the equivalence is held by the
 * tests both modules run.
 *
 * <p>Writes happen in whatever transaction the caller has: {@link #insert} in the caller's business
 * transaction (that is the whole point of an outbox), the relay's marks in none, each on its own,
 * so one failure does not undo an already-dispatched row.
 */
public interface OutboxStore {

  /**
   * Insert one row.
   *
   * @throws org.springframework.dao.DuplicateKeyException if the event id is already present — the
   *     engine decides whether that is an error or an idempotent no-op
   */
  void insert(OutboxInsert row);

  /**
   * The rows to dispatch now, oldest first.
   *
   * <p>A row is due when it is unsent, has attempts below {@code maxAttempts}, and its {@code
   * next_attempt_at} has passed (or was never set). Order is {@code created_at} then the identity
   * column, so an aggregate's events go out in the order they were written.
   *
   * <p>An implementation must also hold a row back while an <em>earlier</em> event of the same
   * {@code subject} is still live but not yet due — i.e. backing off — because that earlier event
   * cannot go out in this batch and a later one must not overtake it. An earlier event that is
   * itself due is not a blocker (both ride this batch in order, and an in-batch failure holds the
   * rest back); nor is a dead-lettered one (it has left the table) or one that has exhausted its
   * attempts. A null or blank subject carries no ordering key, so it neither blocks nor is blocked.
   */
  List<PendingMessage> findDue(Instant now, int maxAttempts, int batchSize);

  /** Record that a row was delivered. */
  void markSent(String eventId, Instant sentAt);

  /** Count a failed attempt and push the row's next attempt out to {@code nextAttemptAt}. */
  void scheduleRetry(String eventId, Instant nextAttemptAt);

  /**
   * Push a row's next attempt out <em>without</em> counting an attempt.
   *
   * <p>For the one case where a row must be spaced out but has not failed a delivery: the relay
   * gave up on it and the dead-letter store was unavailable. Leaving attempts untouched keeps the
   * row selectable, so it retries the move until the store recovers instead of crossing
   * max-attempts and being stranded in the table with nothing looking at it.
   */
  void backOffWithoutAttempt(String eventId, Instant nextAttemptAt);

  /**
   * Delete rows already sent before {@code sentBefore}.
   *
   * @return how many rows were removed
   */
  int deleteSentBefore(Instant sentBefore);
}
