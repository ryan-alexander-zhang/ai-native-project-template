package com.aipersimmon.ddd.outbox;

import java.time.Instant;

/**
 * A stored integration event handed to a {@link OutboxDispatcher} for delivery:
 * the transport metadata plus the serialized payload. Decoupled from the storage
 * row so the dispatcher does not depend on how the outbox persists it.
 */
public record OutboxMessage(
        String eventId,
        String type,
        int version,
        String payload,
        Instant occurredAt,
        String traceId) {
}
