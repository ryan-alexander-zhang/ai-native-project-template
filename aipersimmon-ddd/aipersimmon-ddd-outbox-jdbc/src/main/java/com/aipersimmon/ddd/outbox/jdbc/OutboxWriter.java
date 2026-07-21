package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.application.DurableIntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Captured;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes an integration event into the outbox table in the caller's transaction.
 * It stamps the transport metadata (an event id, the event's class name as the type,
 * the event's declared version, the current time) and the causal chain from the emitting
 * command's {@link CommandContext} — correlation, causation (the command's message
 * id), and trace — into an {@link EventEnvelope}, serializes the event payload to
 * JSON, and inserts one row. Being part of the caller's transaction, the row commits
 * atomically with the aggregate change.
 *
 * <p>{@link #publish} mints a fresh event id for a new event; {@link #publishAs} reuses
 * the persisted identity a durable relay assigns (event id equal to the effect id) and
 * inserts idempotently, so an at-least-once redelivery of the same staged effect writes
 * the row once.
 */
public class OutboxWriter implements DurableIntegrationEvents {

    private static final String INSERT =
            "INSERT INTO aipersimmon_outbox "
            + "(event_id, source, type, version, payload, occurred_at, subject, "
            + "correlation_id, causation_id, traceparent, trace_state, sent, attempts, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String source;
    private final StoreAndForwardTracer tracer;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, String source) {
        this(jdbc, objectMapper, clock, source, NoOpStoreAndForwardTracer.INSTANCE);
    }

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, String source,
                        StoreAndForwardTracer tracer) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.source = source;
        this.tracer = tracer;
    }

    @Override
    public void publish(IntegrationEvent event, CommandContext context) {
        // A brand-new event caused by the command described by context: mint a fresh event
        // id and record the command (context.messageId()) as the cause.
        write(event, UUID.randomUUID().toString(), context.correlationId(), context.messageId(), false);
    }

    @Override
    public void publishAs(IntegrationEvent event, CommandContext context) {
        // A staged effect replayed by the durable relay: identity and causal chain were minted
        // and persisted upstream, so stamp them verbatim — event id = the persisted effect id
        // (context.messageId()), cause = context.causationId(). The insert is idempotent: a
        // redelivery re-inserting the same event id collapses onto the existing row, so the one
        // logical event is written once and the downstream inbox dedupes redeliveries by it.
        write(event, context.messageId(), context.correlationId(), context.causationId(), true);
    }

    private void write(IntegrationEvent event, String eventId, String correlationId, String causationId,
                       boolean idempotent) {
        String payload = serialize(event);
        // Capture the trace context active on this (writing) thread so the relay can restore it
        // when it dispatches the row later on the scheduler thread — the one hop ambient context
        // and Kafka producer auto-instrumentation cannot bridge across the outbox table.
        Captured captured = tracer.captureCurrent();
        EventEnvelope<IntegrationEvent> envelope = new EventEnvelope<>(
                eventId,
                source,
                IntegrationEvent.eventTypeOf(event.getClass()),
                IntegrationEvent.eventVersionOf(event.getClass()),
                clock.instant(),
                event.subject(),
                correlationId,
                causationId,
                event);
        try {
            jdbc.update(INSERT,
                    envelope.eventId(),
                    envelope.source(),
                    envelope.type(),
                    envelope.version(),
                    payload,
                    Timestamp.from(envelope.occurredAt()),
                    envelope.subject(),
                    envelope.correlationId(),
                    envelope.causationId(),
                    captured.traceparent(),
                    captured.traceState(),
                    false,
                    0,
                    Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException alreadyWritten) {
            if (!idempotent) {
                throw alreadyWritten;
            }
            // Same event id already in the outbox: an earlier delivery of this staged effect
            // committed the row before the relay could mark the effect delivered. Nothing to do.
        }
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
