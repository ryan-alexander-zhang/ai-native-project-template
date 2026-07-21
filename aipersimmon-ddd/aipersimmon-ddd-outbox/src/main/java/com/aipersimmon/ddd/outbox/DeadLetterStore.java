package com.aipersimmon.ddd.outbox;

/**
 * Where the relay puts a message it has given up delivering — a permanent failure, or one that
 * exhausted its retries — so "gave up" means "set aside for inspection and replay", not "silently
 * lost". This is the piece the outbox was missing: without it a spent message either lingered
 * forever as an unselectable row or was dropped with no record.
 *
 * <p>A storage starter backs this with a dedicated {@code aipersimmon_dead_letter} table. {@link
 * #store} <strong>moves</strong> the row out of the outbox in one transaction, so a message is
 * never both live and dead, and the hot outbox table stays free of tombstones. {@link #replay}
 * moves it back for another attempt. Override the bean to route dead letters elsewhere (raise an
 * alert, forward to an external quarantine topic).
 */
public interface DeadLetterStore {

  /** Why a message was given up on — recorded with the dead letter for triage. */
  enum Reason {
    /** A permanent failure: dead-lettered on the first failure, no retries spent. */
    PERMANENT,
    /** A transient failure that never recovered within {@code max-attempts}. */
    RETRIES_EXHAUSTED
  }

  /**
   * Moves a spent message from the outbox into the dead-letter store, atomically.
   *
   * @param message the message being given up on
   * @param attempts how many delivery attempts were made (including the last failure)
   * @param reason why it was given up on
   * @param lastError a short description of the final failure (class and message)
   */
  void store(OutboxMessage message, int attempts, Reason reason, String lastError);

  /**
   * Moves a dead letter back into the outbox for another delivery attempt, resetting its delivery
   * bookkeeping (unsent, zero attempts, eligible immediately). Intended for an operator or a
   * support tool once the underlying cause is fixed.
   *
   * @param eventId the dead letter's event id
   * @return {@code true} if a dead letter with that id existed and was requeued
   */
  boolean replay(String eventId);
}
