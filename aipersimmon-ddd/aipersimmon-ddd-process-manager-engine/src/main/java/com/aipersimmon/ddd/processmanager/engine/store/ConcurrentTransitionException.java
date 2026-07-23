package com.aipersimmon.ddd.processmanager.engine.store;

/**
 * A store-neutral signal that a transition could not be appended because a concurrent transaction
 * already recorded a transition for the same {@code (instance_id, input_message_id)} — the durable
 * dedup key. It is a retriable optimistic-concurrency conflict, not a business error: the runtime
 * catches it alongside {@code StaleProcessRevisionException} and re-runs the advance, which then
 * observes the committed transition and folds into it as an idempotent duplicate.
 *
 * <p>Each storage backend maps its native unique-constraint violation (for example Spring's {@code
 * DuplicateKeyException}) to this type, so the persistence-agnostic runtime never depends on a
 * backend's exception hierarchy.
 */
public class ConcurrentTransitionException extends RuntimeException {

  public ConcurrentTransitionException(String message, Throwable cause) {
    super(message, cause);
  }
}
