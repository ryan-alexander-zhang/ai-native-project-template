package com.aipersimmon.ddd.core.id;

/**
 * Mints the framework's per-row / per-message identifiers — a command's message id, an integration
 * event's id, a process instance / transition / effect / deadline id, an operation-log record id.
 *
 * <p>The contract is a globally-unique, <strong>time-ordered</strong> identifier string. Time
 * ordering is what makes these ids cheap to index at scale: a monotonic key inserts near the tail
 * of a B-tree instead of scattering random writes across it. The default implementation ({@code
 * Uuidv7IdGenerator} in {@code aipersimmon-ddd-id}) returns a UUIDv7, which keeps the 36-char UUID
 * shape so it drops in wherever {@code UUID.randomUUID().toString()} was used.
 *
 * <p>This is a pure, zero-dependency SPI so it can live in framework-free {@code
 * aipersimmon-ddd-core}: the library implementation carrying a UUID generator lives in a separate
 * module and is wired by auto-configuration. Callers that hold a {@code Supplier<String>} adapt via
 * {@code idGenerator::newId}.
 *
 * <p>The returned id is opaque: callers must not parse it or depend on the embedded timestamp being
 * present, so a v4 value from an environment without the default implementation remains valid.
 *
 * <p>This does <em>not</em> cover values that are deliberately not high-cardinality time-ordered
 * keys: a {@code tenant_id} discriminator, client-supplied web idempotency/nonce keys, an edge
 * {@code requestId}, or a lease {@code WorkerId}.
 */
@FunctionalInterface
public interface IdGenerator {

  /** A globally-unique, time-ordered identifier string (default: UUIDv7). */
  String newId();
}
