package com.aipersimmon.ddd.processmanager.engine.runtime;

import java.util.function.Supplier;

/**
 * The transaction boundary the engine runs its atomic units of work through — a single {@code
 * advance} (snapshot + transition + staged effects), a claim, or a worker's per-item fire — without
 * depending on a specific transaction API. An implementation joins an outer transaction if present
 * (so the engine composes with an Inbox and command-handler transaction) or opens a local one.
 */
public interface ProcessUnitOfWork {

  /** Run {@code work} inside one transaction and return its result. */
  <R> R execute(Supplier<R> work);

  /**
   * Whether a transaction is already active on the calling thread, so {@link #execute} would join
   * it rather than own it.
   *
   * <p>The engine asks because a conflict inside a joined transaction is not retriable: the failed
   * attempt has already doomed the whole transaction (Spring marks the shared one rollback-only,
   * and a unique-key violation aborts it outright on PostgreSQL), so a second attempt in the same
   * transaction could only fail again. When the unit of work owns the transaction, a conflict rolls
   * back only the attempt and retrying is sound.
   */
  boolean inExistingTransaction();
}
