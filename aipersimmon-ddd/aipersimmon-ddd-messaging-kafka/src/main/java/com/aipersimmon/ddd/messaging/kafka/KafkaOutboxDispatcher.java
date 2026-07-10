package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link OutboxDispatcher} that publishes a stored outbox message to a Kafka topic.
 * The record key is the event id (so a topic partition preserves per-event order
 * and log compaction is possible), the value is the already-serialized JSON
 * payload, and the envelope metadata travels in headers (see
 * {@link IntegrationEventHeaders}).
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
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, message.eventId(), message.payload());
        addHeader(record, IntegrationEventHeaders.TYPE, message.type());
        addHeader(record, IntegrationEventHeaders.EVENT_ID, message.eventId());
        addHeader(record, IntegrationEventHeaders.VERSION, Integer.toString(message.version()));
        addHeader(record, IntegrationEventHeaders.OCCURRED_AT,
                message.occurredAt() == null ? null : message.occurredAt().toString());
        addHeader(record, IntegrationEventHeaders.TRACE_ID, message.traceId());
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
