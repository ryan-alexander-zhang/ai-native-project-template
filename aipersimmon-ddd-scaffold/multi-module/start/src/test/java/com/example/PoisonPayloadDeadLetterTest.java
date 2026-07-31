package com.example;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.messaging.kafka.IntegrationEventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The other half of the contract boundary (issue-00143). The consuming bridge already rejects a
 * record with a missing or malformed {@code ce_*} header as a permanent failure — no retries, dead
 * letter at once. A payload of {@code {}} under perfectly valid headers used to take the slow road
 * instead: it deserialized successfully, its nulls travelled into the inventory handler, and the
 * NPE there was classified as ambiguous — a futile exponential-backoff round before the same dead
 * letter. With the published-language records validating in their compact constructors, Jackson now
 * refuses the payload at parse time as a {@code ValueInstantiationException} — a {@code
 * JsonProcessingException}, which is on the error handler's not-retryable list. This test pins the
 * end-to-end composition: the DLT record's recorded exception is the parse-time refusal, not a
 * handler NPE — and by the classifier the library pins in {@code KafkaErrorHandlerTest}, that
 * class of failure spends one delivery, not a retry budget.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@Import(TestInfrastructure.class)
class PoisonPayloadDeadLetterTest {

  private static final String TOPIC = "ordering.events";
  private static final Duration SETTLE = Duration.ofSeconds(30);

  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired ConsumerFactory<String, String> consumerFactory;

  @Test
  void anEmptyObjectPayloadUnderValidHeadersIsDeadLetteredAsAParseFailure() throws Exception {
    String eventId = "poison-" + UUID.randomUUID();

    kafkaTemplate.send(emptyObjectReadyForFulfilment(eventId)).get();

    ConsumerRecord<String, String> dead = awaitDeadLetter(eventId);
    assertNotNull(dead, "a poison payload must be set aside on " + TOPIC + ".DLT, not dropped");
    String recordedFailure = exceptionHeaders(dead);
    assertTrue(
        recordedFailure.contains("ValueInstantiationException"),
        "the recorded failure must be the parse-time contract refusal (poison, dead-lettered"
            + " without retries) — not an NPE from deep inside a handler after a futile retry"
            + " round. Recorded: "
            + recordedFailure);
  }

  /** Valid CloudEvents headers around a payload that honours nothing of the contract. */
  private ProducerRecord<String, String> emptyObjectReadyForFulfilment(String eventId) {
    ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, eventId, "{}");
    addHeader(record, IntegrationEventHeaders.ID, eventId);
    addHeader(record, IntegrationEventHeaders.TYPE, "com.example.ordering.OrderReadyForFulfilment");
    addHeader(record, IntegrationEventHeaders.SOURCE, "/ordering");
    addHeader(
        record, IntegrationEventHeaders.SPEC_VERSION, IntegrationEventHeaders.SPEC_VERSION_VALUE);
    addHeader(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION, "2");
    addHeader(record, IntegrationEventHeaders.SUBJECT, "order-poison");
    addHeader(record, IntegrationEventHeaders.TENANT_ID, "demo");
    return record;
  }

  private ConsumerRecord<String, String> awaitDeadLetter(String eventId) {
    Properties earliest = new Properties();
    earliest.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("dlt-probe-" + UUID.randomUUID(), null, null, earliest)) {
      consumer.subscribe(List.of(TOPIC + ".DLT"));
      long deadline = System.currentTimeMillis() + SETTLE.toMillis();
      while (System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (eventId.equals(headerValue(record, IntegrationEventHeaders.ID))) {
            return record;
          }
        }
      }
      return null;
    }
  }

  /**
   * Everything the recoverer recorded about why this record died — class names and messages of the
   * exception chain — concatenated, so the assertion can name the failure it expects and the
   * failure message shows what actually happened.
   */
  private static String exceptionHeaders(ConsumerRecord<String, String> record) {
    StringBuilder recorded = new StringBuilder();
    for (Header header : record.headers()) {
      if (header.key().startsWith("kafka_dlt-exception")) {
        recorded
            .append(header.key())
            .append('=')
            .append(new String(header.value(), StandardCharsets.UTF_8))
            .append('\n');
      }
    }
    return recorded.toString();
  }

  private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private static String headerValue(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
