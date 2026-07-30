package com.aipersimmon.ddd.processmanager.engine.store;

/**
 * An in-memory store that can be rolled back, so a test's unit of work is a transaction rather than
 * a pass-through.
 *
 * <p>This is not a convenience. Some of the engine's reasoning depends on a failure
 * <em>undoing</em> what the same unit of work already wrote — the deadline worker marks a timer
 * {@code FIRED} before running the advance it triggers, and its retry path then requires the row to
 * be back in {@code IN_FLIGHT} under the same lease, which only holds because the throw rolls the
 * mark back. A double that quietly kept the write would make that path look broken, and — worse the
 * other way round — one that quietly discarded the write would make a genuine atomicity bug look
 * fine.
 */
public interface Snapshottable {

  /** An opaque copy of everything this store would lose on a rollback. */
  Object snapshot();

  /** Restores a snapshot taken earlier. */
  void restore(Object snapshot);
}
