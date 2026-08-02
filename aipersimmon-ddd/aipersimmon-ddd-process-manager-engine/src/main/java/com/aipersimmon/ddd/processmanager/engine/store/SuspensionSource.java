package com.aipersimmon.ddd.processmanager.engine.store;

/**
 * The suspension sources the runtime writes to {@link ProcessInstanceStore#suspend}. The store port
 * keeps the plain {@code String} — that is what the column holds and what an operator reads — but
 * every writer inside the engine names its source through this enum, and the meter binder derives
 * its per-source gauge tags from {@link #values()}. That closes the gap this enum exists for: a new
 * way to suspend an instance cannot ship without its suspensions showing up in the
 * suspended-instance SLI. Rows carrying a source outside this enum (an older version, an operator
 * tool, a hand edit) are not silently dropped either — the meter binder folds them into its {@code
 * OTHER} bucket.
 */
public enum SuspensionSource {
  /** The effect relay exhausted an effect's retries. */
  EFFECT,
  /** The deadline worker exhausted a deadline's retries. */
  DEADLINE,
  /** A parked input failed replay after a suspension was resumed. */
  PARKED_INPUT
}
