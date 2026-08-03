package com.example.samples.s21;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
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
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The publishing side of an evolution: which revision goes on the wire, and which revision the
 * <em>backlog</em> puts there.
 *
 * <p>The relay's schedule is off so each test drives one poll and asserts on what that poll did. The
 * records are read with a plain {@code KafkaConsumer}, because the claim is about the wire contract a
 * foreign consumer sees — and in this sample the wire is the only place the two services meet.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"aipersimmon.ddd.outbox.relay.enabled=false"})
@Import({PostgresServiceConnection.class, KafkaServiceConnection.class, TestKafkaTopics.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class PublishedRevisionTest {

  private static final String NAME = "com.example.samples.ordering.OrderPlaced";

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;
  @Autowired private IntegrationEventCatalog catalog;

  /** Where the broker actually is: {@code @ServiceConnection} contributes this, not the property. */
  @Autowired private KafkaConnectionDetails kafka;

  @Value("${ordering.events-topic}")
  private String topic;

  private KafkaConsumer<String, String> consumer;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM s21_order_line");
    jdbc.update("DELETE FROM s21_order");
    consumer = newConsumer();
    consumer.subscribe(List.of(topic));
    awaitAssignment();
    consumer.seekToBeginning(consumer.assignment());
  }

  @AfterEach
  void tearDown() {
    consumer.close();
  }

  private void awaitAssignment() {
    for (int attempt = 0; attempt < 30 && consumer.assignment().isEmpty(); attempt++) {
      consumer.poll(Duration.ofMillis(200));
    }
    assertThat(consumer.assignment())
        .as("the test consumer was never assigned a partition of %s", topic)
        .isNotEmpty();
  }

  @Test
  void theWireCarriesTheRevisionThisDeployIsAt() {
    String orderId = idOf(place("customer-1", "sku-keyboard", 2, "EU"));

    relay.relay();

    List<ConsumerRecord<String, String>> records = recordsFor(orderId);
    assertThat(records).hasSize(1);
    ConsumerRecord<String, String> record = records.get(0);
    // The logical name never moved. Only the revision did, and it lives in its own attribute — not in
    // the type name, not in the topic. That is what lets one topic carry three revisions at once.
    assertThat(header(record, "ce_type")).isEqualTo(NAME);
    assertThat(header(record, "ce_dataschemaversion")).isEqualTo("3");
    // v2's restructuring and v3's addition, both visible in the payload.
    assertThat(JsonPath.<String>read(record.value(), "$.lines[0].sku")).isEqualTo("sku-keyboard");
    assertThat(JsonPath.<String>read(record.value(), "$.warehouseCode")).isEqualTo("EU");
  }

  @Test
  void abacklogRowShipsAtTheRevisionItWasWrittenAt() {
    // A row this deploy could not have produced: v2, no warehouseCode, written before the bump and
    // still unsent. Inserted by hand precisely because the class that produced it is gone — which is
    // the situation, not a shortcut around it.
    String orderId = "order-" + UUID.randomUUID();
    insertBacklogRow(orderId, 2, "{\"orderId\":\"%s\",\"lines\":[{\"sku\":\"sku-mouse\",\"quantity\":1}]}"
        .formatted(orderId));

    relay.relay();

    List<ConsumerRecord<String, String>> records = recordsFor(orderId);
    assertThat(records).hasSize(1);
    // Nothing re-stamped it. The revision, the payload and the destination were all decided in the
    // publishing transaction and are carried on the row, so the relay is a courier and not a
    // translator. Deleting a revision from the publisher therefore does not stop it being published;
    // draining the backlog does. A consumer's "how long must I keep reading v2" is
    // max(topic retention, publisher backlog drain, dead-letter replay window) — and the middle term
    // is the one that is invisible from the consumer's side.
    assertThat(header(records.get(0), "ce_dataschemaversion")).isEqualTo("2");
    assertThat(records.get(0).value()).doesNotContain("warehouseCode");
  }

  @Test
  void theRetiredRevisionsAreNotInThisServicesTreeAtAll() {
    // The catalog is a scan of this application's own IntegrationEvent classes, so it is a faithful
    // census of what the code can produce or read. One entry, at the current revision.
    assertThat(catalog.lookup(NAME, 3)).isPresent();
    assertThat(catalog.lookup(NAME, 2)).isEmpty();
    assertThat(catalog.lookup(NAME, 1)).isEmpty();
    // The asymmetry that makes a shared contract jar impossible: the consumer in this same sample has
    // all three registered, because all three can still arrive there. Neither census is wrong, and no
    // single artifact can hold both.
  }

  private void insertBacklogRow(String orderId, int version, String payload) {
    // java.sql.Timestamp, not Instant: the PostgreSQL driver refuses to infer a SQL type for an
    // Instant, and MyBatis (which writes the real rows) converts the same way — Timestamp.from — so
    // this row is stored exactly as one the framework wrote.
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        """
        INSERT INTO aipersimmon_outbox
          (event_id, source, type, version, payload, occurred_at, subject, correlation_id,
           created_at, destination)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        "/ordering",
        NAME,
        version,
        payload,
        now,
        orderId,
        UUID.randomUUID().toString(),
        now,
        topic);
  }

  private ResponseEntity<String> place(
      String customerId, String sku, int quantity, String warehouseCode) {
    return http.postForEntity(
        "/orders",
        Map.of(
            "customerId", customerId,
            "lines", List.of(Map.of("sku", sku, "quantity", quantity)),
            "warehouseCode", warehouseCode),
        String.class);
  }

  private String idOf(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return JsonPath.read(response.getBody(), "$.id");
  }

  /** Every record on the topic keyed by this order, read from the beginning. */
  private List<ConsumerRecord<String, String>> recordsFor(String orderId) {
    List<ConsumerRecord<String, String>> collected = new ArrayList<>();
    for (int emptyPolls = 0; emptyPolls < 3; ) {
      ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(1));
      if (polled.isEmpty()) {
        emptyPolls++;
        continue;
      }
      emptyPolls = 0;
      polled.records(topic).forEach(collected::add);
    }
    return collected.stream().filter(record -> orderId.equals(record.key())).toList();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value());
  }

  private KafkaConsumer<String, String> newConsumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            String.join(",", kafka.getBootstrapServers()),
            ConsumerConfig.GROUP_ID_CONFIG, "wire-revision-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
        new StringDeserializer(),
        new StringDeserializer());
  }
}
