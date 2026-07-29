package com.aipersimmon.ddd.outbox.engine.store;

import java.time.Instant;

/**
 * One row to write: the event's wire identity and causal chain, its serialized payload, the
 * destination resolved for it, and the trace context captured from the writing thread.
 *
 * <p>Flat rather than an {@code EventEnvelope} plus extras, because a store adapter's job is to
 * move these values into columns — giving it the envelope would invite it to re-derive what the
 * engine already decided. {@code destination} is null for in-process delivery; {@code
 * traceparent}/{@code traceState} are nullable too: no tracer, no context.
 */
public record OutboxInsert(
    String eventId,
    String source,
    String type,
    int version,
    String payload,
    Instant occurredAt,
    String subject,
    String tenantId,
    String correlationId,
    String causationId,
    String destination,
    String traceparent,
    String traceState,
    Instant createdAt) {}
