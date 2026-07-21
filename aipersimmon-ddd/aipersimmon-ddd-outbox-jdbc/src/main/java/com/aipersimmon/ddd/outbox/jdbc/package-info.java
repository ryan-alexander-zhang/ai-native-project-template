/**
 * Transactional-outbox implementation over {@code JdbcTemplate}: {@link
 * com.aipersimmon.ddd.outbox.jdbc.OutboxWriter} inserts an integration event into the outbox table
 * in the caller's transaction, and {@link com.aipersimmon.ddd.outbox.jdbc.OutboxRelay} dispatches
 * unsent rows through an {@link com.aipersimmon.ddd.outbox.OutboxDispatcher} (from the
 * storage-agnostic outbox core) and marks them sent. Uses no JPA entity, so it never affects a
 * consumer's entity scanning.
 */
package com.aipersimmon.ddd.outbox.jdbc;
