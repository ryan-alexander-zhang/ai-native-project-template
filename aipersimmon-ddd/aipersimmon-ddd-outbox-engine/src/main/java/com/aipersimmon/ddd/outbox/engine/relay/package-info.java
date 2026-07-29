/**
 * Delivery out of the outbox. {@link com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay} drains
 * one batch of due rows — preserving per-aggregate order under partial failure, classifying
 * failures, backing off, and moving a given-up message to the dead-letter store — and {@link
 * com.aipersimmon.ddd.outbox.engine.relay.OutboxRelayScheduler} is the lease-guarded schedule that
 * calls it, kept a separate bean so anything that must drive the relay itself can turn the schedule
 * off rather than race its lock.
 */
package com.aipersimmon.ddd.outbox.engine.relay;
