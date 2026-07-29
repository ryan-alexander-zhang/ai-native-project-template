/**
 * Retention. {@link com.aipersimmon.ddd.outbox.engine.cleanup.OutboxCleanup} deletes rows that were
 * delivered longer ago than the configured window, so the hot table holds live work rather than
 * history. Opt-in: deleting data and choosing how long to keep it are deployment decisions.
 */
package com.aipersimmon.ddd.outbox.engine.cleanup;
