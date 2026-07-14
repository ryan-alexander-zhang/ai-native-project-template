package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link OutboxDispatcher} that publishes a stored outbox message to a Kafka topic
 * using the CloudEvents Kafka binary binding. The record key is the message's
 * partition key — the aggregate {@code subject} when present, else the event id —
 * so one aggregate's events keep to a single partition and stay in order; the value
 * is the already-serialized JSON payload; the CloudEvents attributes travel in
 * {@code ce_}-prefixed headers (see {@link IntegrationEventHeaders}).
 *
 * <p>The send is awaited: the method returns only once the broker has acknowledged,
 * and throws if it fails — so the outbox relay marks the row sent only on success
 * and otherwise leaves it to be retried on the next poll (at-least-once delivery).
 */
public class KafkaOutboxDispatcher implements OutboxDispatcher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void dispatch(OutboxMessage message) {
        String partitionKey = message.subject() != null && !message.subject().isBlank()
                ? message.subject() : message.eventId();
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, partitionKey, message.payload());
        addHeader(record, IntegrationEventHeaders.ID, message.eventId());
        addHeader(record, IntegrationEventHeaders.SOURCE, message.source());
        addHeader(record, IntegrationEventHeaders.SPEC_VERSION, IntegrationEventHeaders.SPEC_VERSION_VALUE);
        addHeader(record, IntegrationEventHeaders.TYPE, message.type());
        addHeader(record, IntegrationEventHeaders.TIME,
                message.occurredAt() == null ? null : message.occurredAt().toString());
        addHeader(record, IntegrationEventHeaders.SUBJECT, message.subject());
        addHeader(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION, Integer.toString(message.version()));
        addHeader(record, IntegrationEventHeaders.CORRELATION_ID, message.correlationId());
        addHeader(record, IntegrationEventHeaders.CAUSATION_ID, message.causationId());
        addHeader(record, IntegrationEventHeaders.TRACE_ID, message.traceId());
        addHeader(record, IntegrationEventHeaders.PARTITION_KEY, partitionKey);
        addHeader(record, IntegrationEventHeaders.CONTENT_TYPE, IntegrationEventHeaders.CONTENT_TYPE_JSON);
        try {
            kafkaTemplate.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted publishing outbox message " + message.eventId() + " to Kafka", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "failed publishing outbox message " + message.eventId() + " to Kafka",
                    e.getCause());
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
