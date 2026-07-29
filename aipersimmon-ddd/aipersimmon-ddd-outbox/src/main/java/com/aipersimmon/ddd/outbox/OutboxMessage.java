package com.aipersimmon.ddd.outbox;

import java.time.Instant;

/**
 * A stored integration event handed to a {@link OutboxDispatcher} for delivery: the transport
 * metadata (including the causal chain — correlation and causation), the serialized payload, and
 * the destination the writer resolved for it. Decoupled from the storage row so the dispatcher does
 * not depend on how the outbox persists it.
 *
 * @param destination where this event is externalized to, or {@code null} for in-process delivery.
 *     Resolved in the writing transaction (see {@link EventDestinations}) rather than when the row
 *     is dispatched, so the row remembers where it was going even if the code that decides has
 *     since changed its mind. A dispatcher that cannot reach an external target must not be handed
 *     a message that names one — the relay refuses that before dispatching.
 */
public record OutboxMessage(
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
    String destination) {}
