package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 *
 * <p>The await is <em>bounded</em> by {@code sendTimeout}: the relay is a single-threaded,
 * {@code fixedDelay} scheduled poll that dispatches rows one at a time and blocks on each
 * broker ack, so an unbounded wait would pin that one thread forever on a single stuck send
 * (broker partition unwritable, metadata stall) — stopping <em>all</em> outbox delivery on
 * that instance and, once the wait outlives the ShedLock lease, letting another instance
 * take the lock and dispatch the same rows concurrently. A timed-out send is cancelled and
 * surfaced as a failure, which the {@code FailureClassifier} treats as transient, so the row
 * stays unsent and is retried with backoff on the next poll. Keep
 * {@code batch-size × sendTimeout} comfortably below the relay's {@code lock-at-most-for} so
 * a whole poll of stalled sends cannot outlive the lease either.
 */
public class KafkaOutboxDispatcher implements OutboxDispatcher {

    /** Default bound on awaiting a broker ack when none is configured. */
    static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final long sendTimeoutMillis;

    public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate, String topic) {
        this(kafkaTemplate, topic, DEFAULT_SEND_TIMEOUT);
    }

    public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate, String topic,
                                 Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeoutMillis = sendTimeout.toMillis();
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
        Future<?> send = kafkaTemplate.send(record);
        try {
            send.get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted publishing outbox message " + message.eventId() + " to Kafka", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "failed publishing outbox message " + message.eventId() + " to Kafka",
                    e.getCause());
        } catch (TimeoutException e) {
            // Do not pin the single relay thread on one stuck send: give up waiting (cancel
            // best-effort) and surface it as a transient failure so the relay leaves the row
            // to be retried with backoff on the next poll.
            send.cancel(true);
            throw new IllegalStateException(
                    "timed out after " + sendTimeoutMillis + "ms publishing outbox message "
                            + message.eventId() + " to Kafka", e);
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
