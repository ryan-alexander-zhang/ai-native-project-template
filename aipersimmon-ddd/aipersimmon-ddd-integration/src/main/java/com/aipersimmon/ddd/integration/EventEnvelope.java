package com.aipersimmon.ddd.integration;

import java.time.Instant;

/**
 * Carries the metadata a transport needs around a typed integration-event
 * payload: a unique event id, a logical type name, the schema version, when the
 * event occurred, and an optional trace id for correlation.
 *
 * <p>A pure data holder: it performs no serialization and reads no clock or
 * random source. An infrastructure component stamps {@code eventId} and
 * {@code occurredAt} when it wraps a payload for sending.
 *
 * @param <T> the integration-event payload type
 */
public record EventEnvelope<T extends IntegrationEvent>(
        String eventId,
        String type,
        int version,
        Instant occurredAt,
        String traceId,
        T payload) {

    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload required");
        }
        // traceId is optional (may be null when no trace context is present).
    }
}
