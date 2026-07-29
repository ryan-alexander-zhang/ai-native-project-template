package com.aipersimmon.ddd.outbox.engine.store;

import com.aipersimmon.ddd.outbox.OutboxMessage;

/**
 * A row selected for dispatch: the dispatcher-facing message, how many attempts it has already
 * consumed, and the trace context to restore around the dispatch.
 *
 * <p>The attempt count is the row's, not the engine's — the relay adds one to it when a dispatch
 * fails, so a lease-expiry re-selection never silently spends part of the retry budget.
 */
public record PendingMessage(
    OutboxMessage message, int attempts, String traceparent, String traceState) {}
