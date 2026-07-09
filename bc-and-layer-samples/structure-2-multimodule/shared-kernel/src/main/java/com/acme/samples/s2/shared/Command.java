package com.acme.samples.s2.shared;

/**
 * Marker for a command — a task-based, state-changing intent (analysis-00005 §5.1;
 * Greg Young / Udi Dahan). Named imperatively, carries the minimal data to perform
 * the intent, and yields only id/metadata via {@code R} (never a read model).
 * Framework-free.
 *
 * @param <R> the result type the handler returns (use {@link Void} for none)
 */
public interface Command<R> {
}
