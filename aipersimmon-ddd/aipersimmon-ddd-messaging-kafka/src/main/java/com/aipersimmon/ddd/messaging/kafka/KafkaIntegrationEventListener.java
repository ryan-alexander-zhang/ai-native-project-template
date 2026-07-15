package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.application.Inbox;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes CloudEvents-encoded integration events from Kafka and hands them to local
 * handlers. For each record it guards against redelivery with the {@link Inbox}
 * (keyed by the {@code ce_id} header); otherwise it resolves the {@code ce_type}
 * (type, version) pair to a local class via the {@link IntegrationEventCatalog} — never
 * loading the producer's class by name, dead-lettering an unknown pair — reconstructs the {@link EventEnvelope} from
 * the {@code ce_} headers and JSON payload, and republishes the envelope through
 * Spring's {@link ApplicationEventPublisher}, so beans with {@code @EventListener}
 * handlers for {@code EventEnvelope<TheEvent>} receive it with the full metadata
 * intact — the inbound adapter is the anti-corruption layer and needs that metadata.
 *
 * <p>The inbox check and the handling run in one transaction, so if a handler
 * fails the inbox record rolls back and Kafka can redeliver the record; because
 * delivery is at-least-once, the inbox is what makes reprocessing safe. If no
 * {@code Inbox} is configured, every record is republished (no deduplication),
 * which is only safe when the handlers are themselves idempotent.
 */
public class KafkaIntegrationEventListener {

    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final Inbox inbox;
    private final IntegrationEventCatalog catalog;
    private final Clock clock;

    /**
     * @param inbox the idempotency guard, or {@code null} to republish every record
     *              without deduplication
     */
    public KafkaIntegrationEventListener(ApplicationEventPublisher publisher,
                                         ObjectMapper objectMapper,
                                         Inbox inbox,
                                         IntegrationEventCatalog catalog) {
        this(publisher, objectMapper, inbox, catalog, Clock.systemUTC());
    }

    public KafkaIntegrationEventListener(ApplicationEventPublisher publisher,
                                         ObjectMapper objectMapper,
                                         Inbox inbox,
                                         IntegrationEventCatalog catalog,
                                         Clock clock) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.inbox = inbox;
        this.catalog = catalog;
        this.clock = clock;
    }

    @KafkaListener(
            topics = "${aipersimmon.ddd.messaging.kafka.topic:aipersimmon.integration-events}",
            groupId = "${aipersimmon.ddd.messaging.kafka.consumer.group-id:${spring.application.name:aipersimmon}}")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        String eventId = header(record, IntegrationEventHeaders.ID);
        if (inbox != null && eventId != null && inbox.alreadyProcessed(eventId)) {
            return;
        }
        EventEnvelope<IntegrationEvent> envelope = reconstruct(record);
        // Carry the payload's concrete type so listeners typed EventEnvelope<TheEvent>
        // match despite erasure.
        ResolvableType type = ResolvableType.forClassWithGenerics(
                EventEnvelope.class, envelope.payload().getClass());
        publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, type));
    }

    private EventEnvelope<IntegrationEvent> reconstruct(ConsumerRecord<String, String> record) {
        String type = header(record, IntegrationEventHeaders.TYPE);
        if (type == null) {
            throw new IllegalStateException(
                    "Kafka record is missing the " + IntegrationEventHeaders.TYPE + " header");
        }
        int version = parseVersion(header(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION));
        Class<? extends IntegrationEvent> eventType = catalog.lookup(type, version)
                .orElseThrow(() -> new UnknownIntegrationEventException(type, version));
        try {
            IntegrationEvent payload = objectMapper.readValue(record.value(), eventType);
            String eventId = orElse(header(record, IntegrationEventHeaders.ID), UUID.randomUUID().toString());
            String source = orElse(header(record, IntegrationEventHeaders.SOURCE), "unknown");
            String subject = header(record, IntegrationEventHeaders.SUBJECT);
            String correlationId = orElse(header(record, IntegrationEventHeaders.CORRELATION_ID), eventId);
            String causationId = header(record, IntegrationEventHeaders.CAUSATION_ID);
            String traceId = header(record, IntegrationEventHeaders.TRACE_ID);
            Instant occurredAt = parseInstant(header(record, IntegrationEventHeaders.TIME));
            return new EventEnvelope<>(
                    eventId, source, type, version, occurredAt, subject,
                    correlationId, causationId, traceId, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to reconstruct integration event of type " + type, e);
        }
    }

    private int parseVersion(String value) {
        return value == null ? 1 : Integer.parseInt(value);
    }

    private Instant parseInstant(String value) {
        return value == null ? clock.instant() : Instant.parse(value);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
