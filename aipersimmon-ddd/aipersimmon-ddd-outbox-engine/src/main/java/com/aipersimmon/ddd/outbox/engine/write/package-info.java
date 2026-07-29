/**
 * The write side. {@link com.aipersimmon.ddd.outbox.engine.write.OutboxWriter} stamps an
 * integration event's wire identity and causal chain onto a row inside the caller's transaction, so
 * the event and the state change that caused it commit together — and refuses to write at all when
 * there is no such transaction.
 */
package com.aipersimmon.ddd.outbox.engine.write;
