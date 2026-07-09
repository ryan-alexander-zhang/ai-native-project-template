package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes an integration event into the outbox table in the caller's transaction.
 * It stamps the transport metadata (a random event id, the event's class name as
 * the type, version 1, the current time) into an {@link EventEnvelope}, serializes
 * the event payload to JSON, and inserts one row. Being part of the caller's
 * transaction, the row commits atomically with the aggregate change.
 */
public class OutboxWriter implements IntegrationEvents {

    private static final String INSERT =
            "INSERT INTO aipersimmon_outbox "
            + "(event_id, type, version, payload, occurred_at, trace_id, sent, attempts, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void publish(IntegrationEvent event) {
        String payload = serialize(event);
        EventEnvelope<IntegrationEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                event.getClass().getName(),
                1,
                clock.instant(),
                null,
                event);
        jdbc.update(INSERT,
                envelope.eventId(),
                envelope.type(),
                envelope.version(),
                payload,
                Timestamp.from(envelope.occurredAt()),
                envelope.traceId(),
                false,
                0,
                Timestamp.from(clock.instant()));
    }

    private String serialize(IntegrationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to serialize integration event: " + event.getClass().getName(), e);
        }
    }
}
