package com.example.samples.s22.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Records written by hand, as a foreign publisher would — which is the only way to produce the records
 * this module is about. A poison record cannot be created by the publisher in this repository, because
 * the publisher's code is correct; it is created by a publisher on a different release, or by a topic
 * two teams both write to.
 */
final class WireRecords {

  static final String ORDERING_TYPE = "com.example.samples.ordering.OrderPlaced";

  private WireRecords() {}

  /** A record this service understands. */
  static ProducerRecord<String, String> order(
      String topic, String orderId, String sku, int quantity) {
    return order(topic, orderId, sku, quantity, UUID.randomUUID().toString());
  }

  /** The same, with a chosen {@code ce_id} — so a test can redeliver the identical message. */
  static ProducerRecord<String, String> order(
      String topic, String orderId, String sku, int quantity, String eventId) {
    String payload =
        "{\"orderId\":\"%s\",\"sku\":\"%s\",\"quantity\":%d}".formatted(orderId, sku, quantity);
    return record(topic, orderId, payload, ORDERING_TYPE, "1", eventId);
  }

  /**
   * A record whose {@code (ce_type, ce_dataschemaversion)} pair no class here answers.
   *
   * <p>The most realistic poison there is, and worth being precise about why: no number of retries
   * conjures a local class, so this is permanently undeliverable <em>for this build</em> — and a later
   * build that adds the class would handle it perfectly. That is what makes the dead-letter topic the
   * right destination rather than a discard: the record is not wrong, it is early.
   */
  static ProducerRecord<String, String> unknownType(String topic, String orderId) {
    return record(
        topic,
        orderId,
        "{\"orderId\":\"%s\",\"whatever\":true}".formatted(orderId),
        "com.example.samples.ordering.OrderRenamedIntoTheFuture",
        "1",
        UUID.randomUUID().toString());
  }

  private static ProducerRecord<String, String> record(
      String topic, String key, String payload, String type, String version, String eventId) {
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
    header(record, "ce_specversion", "1.0");
    header(record, "ce_id", eventId);
    header(record, "ce_source", "/ordering");
    header(record, "ce_type", type);
    header(record, "ce_dataschemaversion", version);
    header(record, "ce_time", Instant.now().toString());
    header(record, "ce_subject", key);
    header(record, "ce_correlationid", UUID.randomUUID().toString());
    header(record, "content-type", "application/json");
    return record;
  }

  static KafkaProducer<String, String> producer(List<String> bootstrapServers) {
    return new KafkaProducer<>(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            String.join(",", bootstrapServers),
            ProducerConfig.ACKS_CONFIG,
            "all"),
        new StringSerializer(),
        new StringSerializer());
  }

  static void send(KafkaProducer<String, String> producer, ProducerRecord<String, String> record) {
    try {
      producer.send(record).get();
    } catch (Exception e) {
      throw new IllegalStateException("could not produce " + record, e);
    }
  }

  private static void header(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(new RecordHeader(name, value.getBytes()));
  }
}
