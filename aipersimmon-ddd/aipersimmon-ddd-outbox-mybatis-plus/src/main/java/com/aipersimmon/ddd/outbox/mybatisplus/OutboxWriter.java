package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Captured;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;

/**
 * Writes an integration event into the outbox table in the caller's transaction
 * through the MyBatis-Plus {@link OutboxMapper}. It stamps the transport metadata
 * (a random event id, the event's class name as the type, the event's declared version, the current
 * time) and the causal chain from the emitting command's {@link CommandContext} —
 * correlation, causation (the command's message id), and trace — into an
 * {@link EventEnvelope}, serializes the event payload to JSON, and inserts one
 * {@link OutboxRecord}. Being part of the caller's transaction, the row commits
 * atomically with the aggregate change.
 */
public class OutboxWriter implements IntegrationEvents {

    private final OutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String source;
    private final StoreAndForwardTracer tracer;

    public OutboxWriter(OutboxMapper mapper, ObjectMapper objectMapper, Clock clock, String source) {
        this(mapper, objectMapper, clock, source, NoOpStoreAndForwardTracer.INSTANCE);
    }

    public OutboxWriter(OutboxMapper mapper, ObjectMapper objectMapper, Clock clock, String source,
                        StoreAndForwardTracer tracer) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.source = source;
        this.tracer = tracer;
    }

    @Override
    public void publish(IntegrationEvent event, CommandContext context) {
        String payload = serialize(event);
        // Capture the writing thread's trace context so the relay can restore it when it
        // dispatches the row later — the outbox hop that ambient context cannot bridge.
        Captured captured = tracer.captureCurrent();
        EventEnvelope<IntegrationEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                source,
                IntegrationEvent.eventTypeOf(event.getClass()),
                IntegrationEvent.eventVersionOf(event.getClass()),
                clock.instant(),
                event.subject(),
                context.correlationId(),
                context.messageId(),
                context.traceId(),
                event);

        OutboxRecord record = new OutboxRecord();
        record.setEventId(envelope.eventId());
        record.setSource(envelope.source());
        record.setType(envelope.type());
        record.setVersion(envelope.version());
        record.setPayload(payload);
        record.setOccurredAt(envelope.occurredAt());
        record.setSubject(envelope.subject());
        record.setCorrelationId(envelope.correlationId());
        record.setCausationId(envelope.causationId());
        record.setTraceId(envelope.traceId());
        record.setTraceparent(captured.traceparent());
        record.setTraceState(captured.traceState());
        record.setSent(false);
        record.setAttempts(0);
        record.setCreatedAt(clock.instant());
        mapper.insert(record);
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
