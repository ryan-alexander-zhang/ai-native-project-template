/**
 * Worker-lease identity and the database-specific effect-claim strategies. {@link
 * com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect} defines an atomic claim of due,
 * per-instance-ordered effects; {@link
 * com.aipersimmon.ddd.processmanager.jdbc.lease.SkipLockedProcessDialect} uses {@code FOR UPDATE
 * SKIP LOCKED} (PostgreSQL/MySQL) and {@link
 * com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect} uses an atomic
 * conditional update (H2). {@link com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId} is a
 * lease identity only, never a business identity.
 */
package com.aipersimmon.ddd.processmanager.jdbc.lease;
