/**
 * Idempotent-consumer implementation over {@code JdbcTemplate}:
 * {@link com.aipersimmon.ddd.inbox.jdbc.JdbcInbox} records each handled message
 * key in an inbox table with a unique constraint, so a redelivered message is
 * detected and skipped. Uses no JPA entity, so it never affects a consumer's
 * entity scanning.
 */
package com.aipersimmon.ddd.inbox.jdbc;
