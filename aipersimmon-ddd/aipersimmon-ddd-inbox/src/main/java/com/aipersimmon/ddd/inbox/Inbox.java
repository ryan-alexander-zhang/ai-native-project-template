package com.aipersimmon.ddd.inbox;

/**
 * Idempotency guard for consuming a message at most once in effect. A consumer calls this before
 * handling a message; the infrastructure layer records the key and reports whether it had already
 * been handled.
 *
 * <p>Call it inside the same transaction as the processing, so that on failure the record rolls
 * back and the message can be retried on redelivery.
 */
public interface Inbox {

  /**
   * Record the given message as handled and report whether it had already been handled.
   *
   * <p>Identity is the <strong>pair</strong> {@code (source, messageKey)}, not the key alone. A
   * message id is only unique <em>within the producer that minted it</em> — that is what
   * CloudEvents requires of {@code ce_id}, and only {@code id} together with {@code source}
   * identifies an event globally. Deduplicating on the key alone would make two events from
   * different producers that happen to share an id look like a redelivery of one, and the second
   * would be dropped without a trace. That costs nothing when every id is a UUID, but producers
   * legitimately use per-source sequence numbers, and a consumer aggregating several producers is
   * exactly where an inbox earns its keep.
   *
   * @param source the producing system's identity ({@code ce_source}); scopes {@code messageKey}
   * @param messageKey the producer-assigned message id ({@code ce_id}), unique within {@code
   *     source}
   * @return {@code true} if the pair was already recorded (skip processing); {@code false} if this
   *     call recorded it for the first time (proceed)
   */
  boolean alreadyProcessed(String source, String messageKey);
}
