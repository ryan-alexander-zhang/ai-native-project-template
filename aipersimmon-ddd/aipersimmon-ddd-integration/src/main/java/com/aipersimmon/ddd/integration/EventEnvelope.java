package com.aipersimmon.ddd.integration;

import java.time.Instant;

/**
 * The metadata a transport needs around a typed integration-event payload, modelled
 * on the <a href="https://cloudevents.io">CloudEvents</a> attributes so the on-the-wire
 * contract is language-neutral and independently evolvable:
 *
 * <ul>
 *   <li>{@code eventId} — CloudEvents {@code id}: this event's unique id (inbox key).
 *   <li>{@code source} — CloudEvents {@code source}: the context that produced it.
 *   <li>{@code type} — CloudEvents {@code type}: the logical event type (never a Java
 *       class name), so consumers map it to their own local type.
 *   <li>{@code version} — the payload schema revision (a {@code dataschemaversion}
 *       extension) and, with {@code type}, the exact resolution key; bump on a payload
 *       schema change (a change to the business fact is a new {@code type} instead).
 *   <li>{@code occurredAt} — CloudEvents {@code time}.
 *   <li>{@code subject} — CloudEvents {@code subject}: the aggregate id, used as the
 *       transport partition/ordering key ({@code null} if none).
 *   <li>{@code correlationId} / {@code causationId} / {@code traceId} — the causal
 *       chain, as CloudEvents extension attributes.
 * </ul>
 *
 * <p>A pure data holder: it performs no serialization and reads no clock or random
 * source. An infrastructure component stamps {@code eventId}, {@code source},
 * {@code occurredAt}, and the causal ids when it wraps a payload for sending.
 *
 * @param <T> the integration-event payload type
 */
public record EventEnvelope<T extends IntegrationEvent>(
        String eventId,
        String source,
        String type,
        int version,
        Instant occurredAt,
        String subject,
        String correlationId,
        String causationId,
        String traceId,
        T payload) {

    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId required");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source required");
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
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload required");
        }
        // subject, causationId, traceId are optional.
    }

    /**
     * The transport partition/ordering key: the {@link #subject} when present, else
     * the {@link #eventId} (which does not preserve per-aggregate order).
     */
    public String partitionKey() {
        return subject != null && !subject.isBlank() ? subject : eventId;
    }
}
