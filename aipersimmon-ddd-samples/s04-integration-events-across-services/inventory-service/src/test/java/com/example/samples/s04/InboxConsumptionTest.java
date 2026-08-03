package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;

/**
 * The consuming side, driven by records this test writes itself.
 *
 * <p>Producing raw records rather than booting the publisher is deliberate: the contract between the
 * two services is the wire format, so a consumer test that starts from bytes on a topic tests the same
 * thing a foreign producer would exercise — and it can produce the records a well-behaved publisher
 * never would, which is where the interesting behaviour is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  ProbeDispatch.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class InboxConsumptionTest {

  private static final String KEYBOARD = "sku-keyboard";
  private static final String ORDERING_TYPE = "com.example.samples.ordering.OrderPlaced";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;

  @Value("${inventory.ordering-events-topic}")
  private String topic;

  private KafkaProducer<String, String> producer;

  @BeforeEach
  void setUp() {
    // Wait until the bridge's consumer actually holds the partition. Producing before that is a coin
    // toss — a record written while the group is still joining may or may not be seen, so the same
    // test passes and fails on consecutive runs, and every emptiness assertion in this class becomes
    // worthless. An assertion is only worth as much as the proof that the instrument is live.
    listeners
        .getListenerContainers()
        .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
    jdbc.update("DELETE FROM aipersimmon_inbox");
    jdbc.update("UPDATE s04_stock SET available = 100, reserved = 0, version = version + 1");
    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafka.getBootstrapServers()),
                ProducerConfig.ACKS_CONFIG,
                "all"),
            new StringSerializer(),
            new StringSerializer());
  }

  @AfterEach
  void tearDown() {
    producer.close();
  }

  @Test
  void aconsumedEventReservesStock() {
    String orderId = "order-" + UUID.randomUUID();

    send(record(orderId, 2, ORDERING_TYPE, 1, "/ordering", UUID.randomUUID().toString()));

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(reserved()).isEqualTo(2));
    assertThat(available()).isEqualTo(98);
    assertThat(inboxCount()).isEqualTo(1);
  }

  @Test
  void aredeliveryOfTheSameMessageChangesNothing() {
    String orderId = "order-" + UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();

    send(record(orderId, 2, ORDERING_TYPE, 1, "/ordering", eventId));
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(reserved()).isEqualTo(2));
    // The same message again — the steady state of an at-least-once transport, not an anomaly.
    send(record(orderId, 2, ORDERING_TYPE, 1, "/ordering", eventId));

    // Nothing to await: the assertion is that nothing further happens. Give the consumer time to
    // have processed it, then assert the effect did not double.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved()).isEqualTo(2));
    assertThat(inboxCount()).isEqualTo(1);
  }

  @Test
  void twoProducersThatMintedTheSameIdAreNotMistakenForEachOther() {
    String eventId = UUID.randomUUID().toString();

    send(record("order-a", 2, ORDERING_TYPE, 1, "/ordering", eventId));
    send(record("order-b", 3, ORDERING_TYPE, 1, "/ordering-eu", eventId));

    // Both are processed, because identity is the pair (source, id) and not the id alone. Keyed on the
    // id, the second would have been dropped as a phantom duplicate — silently, with the reservation
    // simply missing. It costs nothing while every producer mints UUIDs and breaks the day one uses
    // sequence numbers.
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(reserved()).isEqualTo(5));
    assertThat(inboxCount()).isEqualTo(2);
  }

  @Test
  void anunknownEventTypeIsDeadLetteredRatherThanRetriedForever() {
    send(
        record(
            "order-unknown",
            1,
            "com.example.samples.ordering.SomethingWeNeverHeardOf",
            1,
            "/ordering",
            UUID.randomUUID().toString()));

    // Poison: no number of retries conjures a local class for an unknown type, so it goes to the DLT
    // at once. The partition keeps moving, which is the point — one bad record must not stop the rest.
    assertThat(awaitDeadLetter("order-unknown")).hasSize(1);
    assertThat(reserved()).isZero();
  }

  @Test
  void aversionThisConsumerDoesNotKnowIsAlsoPoison() {
    send(record("order-v2", 1, ORDERING_TYPE, 2, "/ordering", UUID.randomUUID().toString()));

    // Resolution is the exact pair (name, version) with no implicit fallback across versions — so a
    // payload bump the consumer has not adopted dead-letters instead of being silently misread as v1.
    // Making that survivable is S21's subject.
    assertThat(awaitDeadLetter("order-v2")).hasSize(1);
    assertThat(reserved()).isZero();
  }

  private ProducerRecord<String, String> record(
      String orderId, int quantity, String type, int version, String source, String eventId) {
    String payload =
        "{\"orderId\":\"%s\",\"lines\":[{\"sku\":\"%s\",\"quantity\":%d}]}"
            .formatted(orderId, KEYBOARD, quantity);
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, orderId, payload);
    header(record, "ce_specversion", "1.0");
    header(record, "ce_id", eventId);
    header(record, "ce_source", source);
    header(record, "ce_type", type);
    header(record, "ce_dataschemaversion", String.valueOf(version));
    header(record, "ce_time", Instant.now().toString());
    header(record, "ce_subject", orderId);
    header(record, "ce_tenantid", "__root__");
    header(record, "ce_correlationid", UUID.randomUUID().toString());
    header(record, "content-type", "application/json");
    return record;
  }

  private static void header(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(new RecordHeader(name, value.getBytes()));
  }

  private void send(ProducerRecord<String, String> record) {
    try {
      producer.send(record).get();
    } catch (Exception e) {
      throw new IllegalStateException("could not produce " + record, e);
    }
  }

  /**
   * The dead-lettered records for one order. Filtered by key rather than "any record on the DLT":
   * the topic keeps everything every test put there, so an unfiltered assertion would pass because
   * some *other* test's record had been dead-lettered.
   */
  private List<ConsumerRecord<String, String>> awaitDeadLetter(String orderId) {
    List<ConsumerRecord<String, String>> collected = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false"),
            new StringDeserializer(),
            new StringDeserializer())) {
      consumer.subscribe(List.of(topic + ".DLT"));
      for (int attempt = 0; attempt < 40 && collected.isEmpty(); attempt++) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
        polled
            .records(topic + ".DLT")
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

  private int reserved() {
    return jdbc.queryForObject(
        "SELECT reserved FROM s04_stock WHERE sku = ?", Integer.class, KEYBOARD);
  }

  private int available() {
    return jdbc.queryForObject(
        "SELECT available FROM s04_stock WHERE sku = ?", Integer.class, KEYBOARD);
  }

  private long inboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class);
  }
}
