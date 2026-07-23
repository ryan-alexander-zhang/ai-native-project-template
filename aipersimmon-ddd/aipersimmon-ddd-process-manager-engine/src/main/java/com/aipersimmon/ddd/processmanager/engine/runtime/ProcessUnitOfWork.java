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
}
