/**
 * Observability seams for the outbox: a push hook the relay reports through ({@link
 * com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver}) and a pull read of how much is waiting
 * ({@link com.aipersimmon.ddd.outbox.engine.observe.OutboxBacklog}). Both are framework-free, so
 * the engine carries no metrics dependency; the starter binds Micrometer to them when one is
 * present.
 */
package com.aipersimmon.ddd.outbox.engine.observe;
