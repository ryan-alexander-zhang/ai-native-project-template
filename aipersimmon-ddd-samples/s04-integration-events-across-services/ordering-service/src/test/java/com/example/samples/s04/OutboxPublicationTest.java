package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
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
 * The publishing side: what is written, when it leaves, and what it looks like on the wire.
 *
 * <p>The relay's schedule is off, so each test drives one poll and asserts on exactly what it did —
 * the use the library documents for {@code relay.enabled=false}. The records are read with a plain
 * {@code KafkaConsumer} rather than the framework's bridge, because the claim under test is about the
 * <em>wire contract</em> a foreign consumer would see.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"aipersimmon.ddd.outbox.relay.enabled=false"})
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  FailAfterHandling.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class OutboxPublicationTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;

  /**
   * Where the broker actually is.
   *
   * <p>Not {@code @Value("${spring.kafka.bootstrap-servers}")}: a {@code @ServiceConnection} container
   * contributes a {@code KafkaConnectionDetails} bean rather than overriding the property, so the
   * property still reads whatever application.yaml says and a test that trusts it dials an address
   * with nothing behind it. The symptom is a consumer that is never assigned a partition.
   */
  @Autowired private KafkaConnectionDetails kafka;

  @Value("${ordering.events-topic}")
  private String topic;

  private KafkaConsumer<String, String> consumer;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM s04_order_line");
    jdbc.update("DELETE FROM s04_order");
    // A fresh group each time, reading the topic from the start. Records from earlier tests stay on
    // the topic — a broker is not a queue — so every assertion below names the order it is about
    // instead of assuming it is alone. That is also how a real consumer has to be written.
    consumer = newConsumer();
    consumer.subscribe(List.of(topic));
    awaitAssignment();
    consumer.seekToBeginning(consumer.assignment());
  }

  /**
   * Polls until the group has a partition, and fails if it never gets one.
   *
   * <p>Not ceremony: without it a consumer that was never assigned anything reads nothing, and every
   * "assert nothing was published" in this class passes for the wrong reason. An emptiness assertion
   * is only worth as much as the proof that the instrument works.
   */
  private void awaitAssignment() {
    for (int attempt = 0; attempt < 30 && consumer.assignment().isEmpty(); attempt++) {
      consumer.poll(Duration.ofMillis(200));
    }
    assertThat(consumer.assignment())
        .as("the test consumer was never assigned a partition of %s", topic)
        .isNotEmpty();
  }

  @AfterEach
  void tearDown() {
    consumer.close();
  }

  @Test
  void theOrderRowAndTheOutboxRowCommitTogether() {
    place("customer-1", "sku-keyboard", 2, false);

    // One transaction wrote both. The two failure modes this removes are the ones nobody can repair
    // afterwards: an order nobody was told about, and an announcement of an order that does not exist.
    assertThat(count("s04_order")).isEqualTo(1);
    assertThat(count("aipersimmon_outbox")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT type FROM aipersimmon_outbox", String.class))
        .isEqualTo("com.example.samples.ordering.OrderPlaced");
  }

  @Test
  void afailureAfterTheHandlerLeavesNeitherRow() {
    ResponseEntity<String> response =
        place(FailAfterHandling.POISON_CUSTOMER, "sku-keyboard", 1, false);

    assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    assertThat(count("s04_order")).isZero();
    assertThat(count("aipersimmon_outbox")).isZero();
  }

  @Test
  void nothingLeavesTheServiceUntilTheRelayRuns() {
    String orderId = idOf(place("customer-1", "sku-keyboard", 2, false));

    // The row is durable and unsent. Publication has happened as far as the business transaction is
    // concerned, and delivery has not — which is exactly the separation the outbox buys.
    assertThat(unsentCount()).isEqualTo(1);
    assertThat(recordsFor(orderId)).isEmpty();
  }

  @Test
  void therelayShipsOneRecordCarryingTheContractOnItsHeaders() {
    String orderId = idOf(place("customer-1", "sku-keyboard", 2, false));

    relay.relay();

    List<ConsumerRecord<String, String>> records = recordsFor(orderId);
    assertThat(records).hasSize(1);
    ConsumerRecord<String, String> record = records.get(0);
    // The identity a foreign consumer dedups and routes on — none of it in the payload.
    assertThat(header(record, "ce_type")).isEqualTo("com.example.samples.ordering.OrderPlaced");
    assertThat(header(record, "ce_dataschemaversion")).isEqualTo("1");
    assertThat(header(record, "ce_source")).isEqualTo("/ordering");
    assertThat(header(record, "ce_id")).isNotBlank();
    assertThat(header(record, "ce_tenantid")).isEqualTo("__root__");
    assertThat(header(record, "ce_correlationid")).isNotBlank();
    // The ordering key: one aggregate's events stay in one partition, so they stay in order.
    assertThat(header(record, "ce_subject")).isEqualTo(orderId);
    assertThat(record.key()).isEqualTo(orderId);
    // The payload is the contract's data and nothing else.
    assertThat(JsonPath.<String>read(record.value(), "$.orderId")).isEqualTo(orderId);
    assertThat(JsonPath.<String>read(record.value(), "$.lines[0].sku")).isEqualTo("sku-keyboard");
  }

  @Test
  void asecondPollShipsNothingAndTheRowIsMarkedSent() {
    String orderId = idOf(place("customer-1", "sku-keyboard", 2, false));
    relay.relay();
    assertThat(recordsFor(orderId)).hasSize(1);

    relay.relay();

    // Nothing further arrived. (Empty, not "still one": the read above consumed the topic to its
    // end, so this asks whether a second poll produced anything *new*.) Marking the row sent is what
    // makes the second poll a no-op instead of a duplicate delivery.
    assertThat(unsentCount()).isZero();
    assertThat(recordsFor(orderId)).isEmpty();
  }

  @Test
  void aneventWithoutExternalizedNeverReachesTheBroker() {
    String orderId = idOf(place("customer-1", "sku-keyboard", 2, true));

    relay.relay();

    // Same port, same transaction, same outbox row — and no record. Externalization is opt-in per
    // event, so installing a broker does not put every internal signal on the wire.
    assertThat(
            jdbc.queryForObject("SELECT type FROM aipersimmon_outbox", String.class))
        .isEqualTo("com.example.samples.ordering.OrderDrafted");
    assertThat(unsentCount()).isZero();
    assertThat(recordsFor(orderId)).isEmpty();
  }

  private ResponseEntity<String> place(
      String customerId, String sku, int quantity, boolean draftOnly) {
    return http.postForEntity(
        "/orders",
        Map.of(
            "customerId",
            customerId,
            "lines",
            List.of(Map.of("sku", sku, "quantity", quantity)),
            "draftOnly",
            draftOnly),
        String.class);
  }

  private String idOf(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return JsonPath.read(response.getBody(), "$.id");
  }

  /**
   * Every record on the topic whose key is this order, read from the beginning. The relay hands a
   * batch to the producer before waiting on it, so this polls until the topic goes quiet rather than
   * assuming one poll is enough.
   */
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
            ConsumerConfig.GROUP_ID_CONFIG, "wire-contract-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
        new StringDeserializer(),
        new StringDeserializer());
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private long unsentCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = FALSE", Long.class);
  }
}
