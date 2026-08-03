package com.example.samples.s21;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Records as a foreign publisher would write them, at whichever revision the test needs.
 *
 * <p>Hand-built bytes rather than a booted publisher, for the reason S4 established and this sample
 * depends on twice over: the contract between the services <em>is</em> the wire format, and a test that
 * starts from bytes can produce revisions no living publisher can — which is exactly what a consumer in
 * the middle of a migration has to survive.
 */
final class TestWire {

  /** The logical name, unchanged across every revision. Only the version attribute moves. */
  static final String TYPE = "com.example.samples.ordering.OrderPlaced";

  private TestWire() {}

  /** v1: one order, one line, no warehouse — the shape before either later change. */
  static String v1Payload(String orderId, String sku, int quantity) {
    return "{\"orderId\":\"%s\",\"sku\":\"%s\",\"quantity\":%d}".formatted(orderId, sku, quantity);
  }

  /** v2: the restructuring — lines, still no warehouse. */
  static String v2Payload(String orderId, String sku, int quantity) {
    return "{\"orderId\":\"%s\",\"lines\":[{\"sku\":\"%s\",\"quantity\":%d}]}"
        .formatted(orderId, sku, quantity);
  }

  /** v3: the current shape, naming its warehouse. */
  static String v3Payload(String orderId, String sku, int quantity, String warehouse) {
    return "{\"orderId\":\"%s\",\"lines\":[{\"sku\":\"%s\",\"quantity\":%d}],\"warehouseCode\":\"%s\"}"
        .formatted(orderId, sku, quantity, warehouse);
  }

  static ProducerRecord<String, String> record(
      String topic, String orderId, int version, String payload, String eventId) {
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, orderId, payload);
    header(record, "ce_specversion", "1.0");
    header(record, "ce_id", eventId);
    header(record, "ce_source", "/ordering");
    header(record, "ce_type", TYPE);
    header(record, "ce_dataschemaversion", String.valueOf(version));
    header(record, "ce_time", Instant.now().toString());
    header(record, "ce_subject", orderId);
    header(record, "ce_tenantid", "__root__");
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

  /**
   * The dead-lettered records for one order on one topic's DLT.
   *
   * <p>Filtered by key rather than "any record on the DLT": a topic keeps everything every test put
   * there, so an unfiltered assertion passes because <em>another</em> test's record was dead-lettered.
   */
  static List<ConsumerRecord<String, String>> deadLetters(
      List<String> bootstrapServers, String topic, String orderId) {
    String dlt = topic + ".DLT";
    List<ConsumerRecord<String, String>> collected = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", bootstrapServers),
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false"),
            new StringDeserializer(),
            new StringDeserializer())) {
      consumer.subscribe(List.of(dlt));
      for (int attempt = 0; attempt < 40 && collected.isEmpty(); attempt++) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
        polled
            .records(dlt)
            .forEach(
                record -> {
                  if (orderId.equals(record.key())) {
                    collected.add(record);
                  }
                });
      }
    }
    return collected;
  }

  private static void header(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(new RecordHeader(name, value.getBytes()));
  }
}
