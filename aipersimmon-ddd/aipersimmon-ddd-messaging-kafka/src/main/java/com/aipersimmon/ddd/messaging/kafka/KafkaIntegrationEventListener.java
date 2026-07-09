package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.application.Inbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes integration events from Kafka and hands them to local handlers. For
 * each record it guards against redelivery with the {@link Inbox} (keyed by the
 * event id header): if the id was already recorded it skips the record; otherwise
 * it reconstructs the event from the type header and JSON payload and republishes
 * it through Spring's {@link ApplicationEventPublisher}, so beans with
 * {@code @EventListener} handlers receive it — just as they would for an in-process
 * event.
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

    /**
     * @param inbox the idempotency guard, or {@code null} to republish every record
     *              without deduplication
     */
    public KafkaIntegrationEventListener(ApplicationEventPublisher publisher,
                                         ObjectMapper objectMapper,
                                         Inbox inbox) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.inbox = inbox;
    }

    @KafkaListener(
            topics = "${aipersimmon.ddd.messaging.kafka.topic:aipersimmon.integration-events}",
            groupId = "${aipersimmon.ddd.messaging.kafka.consumer.group-id:${spring.application.name:aipersimmon}}")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        String eventId = header(record, IntegrationEventHeaders.EVENT_ID);
        if (inbox != null && eventId != null && inbox.alreadyProcessed(eventId)) {
            return;
        }
        publisher.publishEvent(reconstruct(record));
    }

    private Object reconstruct(ConsumerRecord<String, String> record) {
        String type = header(record, IntegrationEventHeaders.TYPE);
        if (type == null) {
            throw new IllegalStateException(
                    "Kafka record is missing the " + IntegrationEventHeaders.TYPE + " header");
        }
        try {
            Class<?> eventType = Class.forName(type);
            return objectMapper.readValue(record.value(), eventType);
        } catch (ClassNotFoundException | JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to reconstruct integration event of type " + type, e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
