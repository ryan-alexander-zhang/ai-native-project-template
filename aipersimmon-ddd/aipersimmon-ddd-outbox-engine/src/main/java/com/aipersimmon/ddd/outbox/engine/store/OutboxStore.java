package com.aipersimmon.ddd.outbox.engine.store;

import java.time.Instant;
import java.util.List;

/**
 * The one port the outbox engine runs on: every row-level operation the writer, relay and cleanup
 * need, and nothing else.
 *
 * <p>Deliberately narrow. Everything that is a <em>decision</em> — which rows are claimable, in
 * what order, what a failure means, when to give up, how long to back off — lives in the engine, so
 * both storage backends make the same decisions by construction. What is left here is only "how do
 * I read and write this table", which is the one thing a backend genuinely knows better.
 *
 * <p>The claim is the exception that proves the rule: its predicate is not "how to read a table"
 * but the per-aggregate ordering guarantee and the mutual exclusion between instances, so {@link
 * #claimDue} carries that contract in its javadoc and an implementation must honour it rather than
 * invent one. It is expressed as SQL twice because the two backends speak different query
 * languages, and the equivalence is held by the tests both modules run.
 *
 * <p>Writes happen in whatever transaction the caller has: {@link #insert} in the caller's business
 * transaction (that is the whole point of an outbox), everything the relay does in none, each on
 * its own, so one failure does not undo an already-dispatched row.
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
   * Claim up to {@code batchSize} dispatchable rows under {@code lease} and return them in dispatch
   * order ({@code created_at}, then the identity column).
   *
   * <p>A row is claimable when all of these hold:
   *
   * <ul>
   *   <li>it is unsent and has {@code attempts} below {@code maxAttempts};
   *   <li>its {@code next_attempt_at} has passed, or was never set;
   *   <li>it carries no live lease — {@code lease_until} is null or already past;
   *   <li>and, if its {@code subject} is neither null nor blank, <strong>no earlier row of the same
   *       subject is still live</strong>, where earlier is by {@code (created_at, id)} and live
   *       means unsent with {@code attempts} below {@code maxAttempts}.
   * </ul>
   *
   * <p>That last clause is the per-aggregate ordering guarantee, and it is why this is a claim
   * rather than a query. It admits only the <em>head</em> of each subject's live queue, so at most
   * one row per subject can be claimed at any moment anywhere in the deployment — a later event
   * cannot overtake an earlier one no matter how many instances poll concurrently. A row's
   * successors become claimable only once it is sent or dead-lettered (which removes it from the
   * table), so ordering is preserved right up to the point a message is given up on. A row that has
   * exhausted its attempts is not live and therefore blocks nothing: it should have been
   * dead-lettered, and one stranded row must not silence its aggregate forever.
   *
   * <p>Ordering is by write time, so a transaction that inserts with an earlier {@code created_at}
   * but commits later can still be overtaken. That is inherent to ordering an outbox by a timestamp
   * and is unchanged here.
   *
   * <p>The claim must be atomic per row: two instances calling this concurrently must never both
   * win the same row. It runs outside a transaction — each row is claimed on its own, so a slow
   * claim never holds locks across the dispatch that follows.
   */
  List<PendingMessage> claimDue(Instant now, int maxAttempts, int batchSize, OutboxLease lease);

  /**
   * Drop the lease on rows that were claimed but will not be dispatched after all, leaving
   * everything else about them untouched so they are claimable again at once.
   *
   * <p>Without this a row the relay decided not to touch would sit unavailable until its lease
   * expired, and — because it is the head of its subject — would hold that aggregate back with it.
   * Called when a poll runs out of its time budget with rows still claimed, and when a delivery
   * succeeded but recording it failed.
   */
  void release(List<String> eventIds);

  /** Record that a row was delivered, and drop its lease. */
  void markSent(String eventId, Instant sentAt);

  /**
   * Count a failed attempt, push the row's next attempt out to {@code nextAttemptAt}, and drop its
   * lease. The row stays the head of its subject, so backing off holds that aggregate back too —
   * which is the intent.
   */
  void scheduleRetry(String eventId, Instant nextAttemptAt);

  /**
   * Push a row's next attempt out <em>without</em> counting an attempt, and drop its lease.
   *
   * <p>For the one case where a row must be spaced out but has not failed a delivery: the relay
   * gave up on it and the dead-letter store was unavailable. Leaving attempts untouched keeps the
   * row claimable, so it retries the move until the store recovers instead of crossing max-attempts
   * and being stranded in the table with nothing looking at it.
   */
  void backOffWithoutAttempt(String eventId, Instant nextAttemptAt);

  /**
   * Delete rows already sent before {@code sentBefore}.
   *
   * @return how many rows were removed
   */
  int deleteSentBefore(Instant sentBefore);

  /**
   * How much live work is waiting: unsent rows below {@code maxAttempts}, and when the oldest of
   * them was written.
   *
   * <p>Read on demand by a metrics scrape or an operator, never by the relay. One call rather than
   * a count and a minimum, because one scan of the same predicate answers both and a metrics scrape
   * should not cost two round trips per gauge. {@code maxAttempts} is passed in so "live" means the
   * same thing here as it does to {@link #claimDue} — the engine owns that definition.
   */
  PendingBacklog pendingBacklog(int maxAttempts);
}
