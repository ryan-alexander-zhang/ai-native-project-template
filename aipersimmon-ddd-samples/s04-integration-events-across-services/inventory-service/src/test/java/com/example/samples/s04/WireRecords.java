package com.example.samples.s04;

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
 * Records as a foreign publisher would write them, with the two attributes S13 and S15 turn on:
 * {@code ce_tenantid} (who owns this fact) and {@code traceparent} (which trace it arrived on).
 *
 * <p>Writing the record by hand is what makes the consuming half testable at all: the test gets to
 * choose the tenant and the trace, so the assertion is that the consumer <em>used what arrived</em>
 * rather than something it made up locally.
 */
final class WireRecords {

  static final String ORDERING_TYPE = "com.example.samples.ordering.OrderPlaced";

  private WireRecords() {}

  /** A well-formed record for one order, owned by {@code tenant}. */
  static ProducerRecord<String, String> order(
      String topic, String orderId, String sku, int quantity, String tenant) {
    return order(topic, orderId, sku, quantity, tenant, UUID.randomUUID().toString(), null, "/ordering");
  }

  static ProducerRecord<String, String> order(
      String topic,
      String orderId,
      String sku,
      int quantity,
      String tenant,
      String eventId,
      String traceparent,
      String source) {
    String payload =
        "{\"orderId\":\"%s\",\"lines\":[{\"sku\":\"%s\",\"quantity\":%d}]}"
            .formatted(orderId, sku, quantity);
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, orderId, payload);
    header(record, "ce_specversion", "1.0");
    header(record, "ce_id", eventId);
    header(record, "ce_source", source);
    header(record, "ce_type", ORDERING_TYPE);
    header(record, "ce_dataschemaversion", "1");
    header(record, "ce_time", Instant.now().toString());
    header(record, "ce_subject", orderId);
    header(record, "ce_correlationid", UUID.randomUUID().toString());
    header(record, "content-type", "application/json");
    if (tenant != null) {
      header(record, "ce_tenantid", tenant);
    }
    if (traceparent != null) {
      header(record, "traceparent", traceparent);
    }
    return record;
  }

  /** A syntactically valid W3C traceparent for a trace this test invented. */
  static String traceparent(String traceId, String spanId) {
    return "00-" + traceId + "-" + spanId + "-01";
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
