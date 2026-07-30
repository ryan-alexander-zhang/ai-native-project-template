package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.outbox.InFlightDispatch;
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
 * Publishes a stored outbox message to a Kafka topic using the CloudEvents Kafka binary binding.
 * The topic is supplied <em>per message</em> ({@link #dispatch(OutboxMessage, String)}) — it is the
 * event's externalization target, which the {@link RoutingOutboxDispatcher} resolves from the
 * event's {@code @Externalized} annotation — so different event types can go to different named
 * topics. The record key is the message's partition key — the aggregate {@code subject} when
 * present, else the event id — so one aggregate's events keep to a single partition and stay in
 * order; the value is the already-serialized JSON payload; the CloudEvents attributes travel in
 * {@code ce_}-prefixed headers (see {@link IntegrationEventHeaders}).
 *
 * <p>This is the Kafka <em>leg</em> the router delegates to, not itself the outbox's single {@code
 * OutboxDispatcher}: routing (LOCAL vs which EXTERNAL topic) is decided by the {@link
 * RoutingOutboxDispatcher}, which owns that role.
 *
 * <p>Delivery is confirmed before the relay records the row: {@link #dispatch} returns only once
 * the broker has acknowledged and throws if it fails, so a row is marked sent only on success and
 * is otherwise left to be retried on the next poll (at-least-once delivery).
 *
 * <p>Handing the record over and waiting for its acknowledgement are separable — {@link
 * #beginDispatch} does the first and returns the second — because a Kafka send is asynchronous
 * anyway. The relay hands a whole claimed batch over and only then waits, so the batch costs one
 * broker round trip instead of one per message and the producer gets records to batch and pipeline
 * as it was designed to. It also lets the producer's own batching work at all: waiting for each ack
 * before writing the next record meant every batch held exactly one record.
 *
 * <p>The wait is <em>bounded</em> by {@code sendTimeout}, measured from the moment the record was
 * handed over rather than from when the wait begins — so a batch of stalled sends costs one timeout
 * in total, not one per record, since they are all in flight together. The bound exists because the
 * relay is a single-threaded, {@code fixedDelay} scheduled poll: an unbounded wait would pin that
 * one thread forever on a stuck send (broker partition unwritable, metadata stall), stopping
 * <em>all</em> outbox delivery on that instance and, once the wait outlives the relay's lease on
 * the row, letting another instance dispatch that row too. A timed-out send is cancelled and
 * surfaced as a failure, which the {@code FailureClassifier} treats as transient, so the row stays
 * unsent and is retried with backoff on the next poll. Keep {@code sendTimeout} below half of
 * {@code outbox.relay.lease-duration}; {@code batch-size} does not enter into that arithmetic.
 */
public class KafkaOutboxDispatcher {

  /** Default bound on awaiting a broker ack when none is configured. */
  static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(30);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final long sendTimeoutMillis;

  public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate) {
    this(kafkaTemplate, DEFAULT_SEND_TIMEOUT);
  }

  public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate, Duration sendTimeout) {
    this.kafkaTemplate = kafkaTemplate;
    this.sendTimeoutMillis = sendTimeout.toMillis();
  }

  /**
   * Publishes the message to {@code topic} and waits for the broker to acknowledge it. The topic is
   * the event's resolved externalization target, chosen by the {@link RoutingOutboxDispatcher}.
   */
  public void dispatch(OutboxMessage message, String topic) {
    beginDispatch(message, topic).awaitDelivery();
  }

  /**
   * Hands the message to the producer for {@code topic} without waiting, returning the pending
   * acknowledgement. The deadline is fixed here, at hand-over, so a batch handed over together and
   * waited on afterwards shares one timeout rather than serialising one each.
   */
  public InFlightDispatch beginDispatch(OutboxMessage message, String topic) {
    Future<?> send = kafkaTemplate.send(record(message, topic));
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sendTimeoutMillis);
    return () -> awaitAck(send, deadlineNanos, message.eventId());
  }

  private void awaitAck(Future<?> send, long deadlineNanos, String eventId) {
    try {
      send.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted publishing outbox message " + eventId + " to Kafka", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(
          "failed publishing outbox message " + eventId + " to Kafka", e.getCause());
    } catch (TimeoutException e) {
      // Do not pin the single relay thread on one stuck send: give up waiting (cancel
      // best-effort) and surface it as a transient failure so the relay leaves the row
      // to be retried with backoff on the next poll.
      send.cancel(true);
      throw new IllegalStateException(
          "timed out after "
              + sendTimeoutMillis
              + "ms publishing outbox message "
              + eventId
              + " to Kafka",
          e);
    }
  }

  private static ProducerRecord<String, String> record(OutboxMessage message, String topic) {
    String partitionKey =
        message.subject() != null && !message.subject().isBlank()
            ? message.subject()
            : message.eventId();
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, partitionKey, message.payload());
    addHeader(record, IntegrationEventHeaders.ID, message.eventId());
    addHeader(record, IntegrationEventHeaders.SOURCE, message.source());
    addHeader(
        record, IntegrationEventHeaders.SPEC_VERSION, IntegrationEventHeaders.SPEC_VERSION_VALUE);
    addHeader(record, IntegrationEventHeaders.TYPE, message.type());
    addHeader(
        record,
        IntegrationEventHeaders.TIME,
        message.occurredAt() == null ? null : message.occurredAt().toString());
    addHeader(record, IntegrationEventHeaders.SUBJECT, message.subject());
    addHeader(
        record, IntegrationEventHeaders.DATA_SCHEMA_VERSION, Integer.toString(message.version()));
    addHeader(record, IntegrationEventHeaders.TENANT_ID, message.tenantId());
    addHeader(record, IntegrationEventHeaders.CORRELATION_ID, message.correlationId());
    addHeader(record, IntegrationEventHeaders.CAUSATION_ID, message.causationId());
    addHeader(record, IntegrationEventHeaders.PARTITION_KEY, partitionKey);
    addHeader(
        record, IntegrationEventHeaders.CONTENT_TYPE, IntegrationEventHeaders.CONTENT_TYPE_JSON);
    return record;
  }

  private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
    if (value != null) {
      record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
  }
}
