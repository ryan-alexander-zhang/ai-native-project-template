/**
 * The JDBC data-access layer over the four-table model:
 * {@link com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore} (the
 * current snapshot, with optimistic update),
 * {@link com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore} (the
 * append-only log and input dedup),
 * {@link com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore} (staged
 * command/event effects), and
 * {@link com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore}
 * (scheduled timers).
 *
 * <p>These are internal runtime implementation, not a business extension point; a
 * consumer does not subclass a store to change the table shape. Codec bytes are
 * persisted as base64 text so any codec fits the text payload columns.
 */
package com.aipersimmon.ddd.processmanager.jdbc.store;
