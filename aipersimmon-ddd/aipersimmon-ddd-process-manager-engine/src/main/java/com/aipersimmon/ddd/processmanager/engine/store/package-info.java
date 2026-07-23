/**
 * The JDBC data-access layer over the four-table model: {@link
 * com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore} (the current snapshot, with
 * optimistic update), {@link
 * com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionStore} (the append-only log and
 * input dedup), {@link com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectStore} (staged
 * command/event effects), and {@link
 * com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore} (scheduled timers).
 *
 * <p>These are internal runtime implementation, not a business extension point; a consumer does not
 * subclass a store to change the table shape. Codec bytes are persisted as base64 text so any codec
 * fits the text payload columns.
 */
package com.aipersimmon.ddd.processmanager.engine.store;
