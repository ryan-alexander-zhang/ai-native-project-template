package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aipersimmon.ddd.outbox.InFlightDispatch;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Verifies the Kafka leg maps an outbox message onto a producer record for the given topic — key,
 * value, and envelope headers — and that a broker send failure surfaces so the outbox relay leaves
 * the row to be retried.
 */
class KafkaOutboxDispatcherTest {

  private final OutboxMessage message =
      new OutboxMessage(
          "evt-1",
          "/ordering",
          "OrderPlaced",
          1,
          "{\"orderId\":\"o-1\"}",
          Instant.parse("2026-01-01T00:00:00Z"),
          "o-1",
          "acme",
          "corr-1",
          "cause-1",
          "ordering.events");

  @Test
  @SuppressWarnings("unchecked")
  void publishesPayloadAsValueWithEnvelopeHeaders() {
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    doReturn(CompletableFuture.completedFuture(null))
        .when(template)
        .send(any(ProducerRecord.class));

    new KafkaOutboxDispatcher(template).dispatch(message, "orders");

    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(template).send(captor.capture());
    ProducerRecord<String, String> record = captor.getValue();

    assertEquals("orders", record.topic());
    assertEquals(
        "o-1", record.key(), "the aggregate subject is the partition key, not the event id");
    assertEquals("{\"orderId\":\"o-1\"}", record.value());
    assertEquals("evt-1", header(record, IntegrationEventHeaders.ID));
    assertEquals("/ordering", header(record, IntegrationEventHeaders.SOURCE));
    assertEquals("1.0", header(record, IntegrationEventHeaders.SPEC_VERSION));
    assertEquals("OrderPlaced", header(record, IntegrationEventHeaders.TYPE));
    assertEquals("o-1", header(record, IntegrationEventHeaders.SUBJECT));
    assertEquals("1", header(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION));
    assertEquals("corr-1", header(record, IntegrationEventHeaders.CORRELATION_ID));
    assertEquals("cause-1", header(record, IntegrationEventHeaders.CAUSATION_ID));
    assertEquals("acme", header(record, IntegrationEventHeaders.TENANT_ID));
    assertEquals("o-1", header(record, IntegrationEventHeaders.PARTITION_KEY));
    assertEquals("application/json", header(record, IntegrationEventHeaders.CONTENT_TYPE));
    assertEquals("2026-01-01T00:00:00Z", header(record, IntegrationEventHeaders.TIME));
  }

  @Test
  @SuppressWarnings("unchecked")
  void doesNotBlockTheRelayThreadForeverWhenTheBrokerNeverAcknowledges() {
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    // A send whose ack never arrives (broker partition unwritable, metadata stall, ...).
    doReturn(new CompletableFuture<>()).when(template).send(any(ProducerRecord.class));

    KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(template, Duration.ofMillis(200));

    // The single relay thread must not be pinned indefinitely on one stuck send: the
    // bounded await surfaces as a (transient) IllegalStateException — not a permanent
    // failure — so the relay leaves the row to be retried on the next poll. The outer
    // preemptive timeout is generous relative to the 200ms send bound; before the fix
    // (an unbounded get) dispatch never returns and this trips.
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () ->
            assertThrows(
                IllegalStateException.class, () -> dispatcher.dispatch(message, "orders")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void surfacesBrokerFailureSoTheRowIsRetried() {
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    doReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
        .when(template)
        .send(any(ProducerRecord.class));

    KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(template);
    assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(message, "orders"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handsEveryRecordToTheProducerBeforeWaitingOnAnyAcknowledgement() {
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    List<CompletableFuture<Object>> acks =
        List.of(new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
    AtomicInteger sends = new AtomicInteger();
    doAnswer(invocation -> acks.get(sends.getAndIncrement()))
        .when(template)
        .send(any(ProducerRecord.class));

    KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(template);
    List<InFlightDispatch> inFlight =
        List.of(
            dispatcher.beginDispatch(message, "orders"),
            dispatcher.beginDispatch(message, "orders"),
            dispatcher.beginDispatch(message, "orders"));

    // The whole batch is with the producer while none of it has been acknowledged — which is
    // what makes a poll cost one broker round trip instead of one per message, and what gives
    // the producer records to batch at all.
    assertEquals(3, sends.get(), "every record is handed over before anything is waited on");
    acks.forEach(ack -> ack.complete(null));
    inFlight.forEach(InFlightDispatch::awaitDelivery);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aBatchOfStalledSendsCostsOneTimeoutRatherThanOnePerMessage() {
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    // Acks that never arrive: the broker has taken every record and gone quiet.
    doReturn(new CompletableFuture<>()).when(template).send(any(ProducerRecord.class));

    KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(template, Duration.ofMillis(400));
    List<InFlightDispatch> inFlight =
        IntStream.range(0, 5).mapToObj(i -> dispatcher.beginDispatch(message, "orders")).toList();

    // Each wait runs to the deadline fixed when its record was handed over, and those deadlines
    // are all within a moment of each other — so five stalled sends expire together at ~400ms
    // rather than serialising into 2s. A per-wait timeout would trip the bound below.
    assertTimeoutPreemptively(
        Duration.ofMillis(1200),
        () ->
            inFlight.forEach(
                pending -> assertThrows(IllegalStateException.class, pending::awaitDelivery)));
  }

  private static String header(ProducerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
