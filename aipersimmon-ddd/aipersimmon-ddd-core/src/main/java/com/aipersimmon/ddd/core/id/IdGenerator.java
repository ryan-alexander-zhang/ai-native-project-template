package com.aipersimmon.ddd.core.id;

/**
 * Mints the framework's per-row / per-message identifiers — a command's message id, an integration
 * event's id, a process instance / transition / effect / deadline id, an operation-log record id.
 *
 * <p>The contract is a globally-unique, <strong>time-ordered</strong> identifier string. Time
 * ordering is what makes these ids cheap to index at scale: a monotonic key inserts near the tail
 * of a B-tree instead of scattering random writes across it. The default implementation ({@code
 * Uuidv7IdGenerator} in {@code aipersimmon-ddd-id-spring-boot-starter}) returns a UUIDv7, which
 * keeps the 36-char UUID shape so it drops in wherever {@code UUID.randomUUID().toString()} was
 * used.
 *
 * <p>This is a pure, zero-dependency SPI so it can live in framework-free {@code
 * aipersimmon-ddd-core}: the library implementation carrying a UUID generator lives in a separate
 * module and is wired by auto-configuration. Callers that hold a {@code Supplier<String>} adapt via
 * {@code idGenerator::newId}.
 *
 * <p>The returned id is opaque: callers must not parse it or depend on the embedded timestamp being
 * present, so a v4 value from an environment without the default implementation remains valid.
 *
 * <p>Use it for a <strong>business aggregate's or entity's primary key</strong> too, whenever the
 * application mints that key itself rather than taking a client-supplied natural key. Aggregate
 * tables are usually the highest-volume tables in the schema, so a time-ordered key pays off most
 * there — {@code UUID.randomUUID()} on an aggregate primary key is exactly the scattered-write
 * pattern this SPI exists to remove.
 *
 * <p>This does <em>not</em> cover values that are deliberately not high-cardinality time-ordered
 * keys: a {@code tenant_id} discriminator, client-supplied web idempotency/nonce keys, an edge
 * {@code requestId}, or a lease {@code WorkerId}. Nor does it cover a natural key the business
 * supplies (a SKU, a customer code) — those are not the framework's to mint.
 */
@FunctionalInterface
public interface IdGenerator {

  /** A globally-unique, time-ordered identifier string (default: UUIDv7). */
  String newId();
}
