package com.example.samples.s12.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s12.ordering.application.PlaceOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
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
 * The other clock: the same projection, updated by a record arriving over a broker.
 *
 * <p>Everything in {@code ProjectionConsistencyTest} happens with no lag at all, because the order's own facts
 * reach the projection through an in-process domain event inside the writing transaction. Everything here is
 * eventually consistent, because the product name reaches it through a topic. <strong>Same table, two
 * clocks</strong> — and a list row can therefore be simultaneously up to date about the order and behind about
 * the product name. That is not a defect to fix; it is what "the name belongs to another context" means, and a
 * design that hid it would only be guessing on the customer's behalf.
 *
 * <p>Records are produced by hand rather than by booting the catalogue service. The contract between the two is
 * the wire format, so starting from bytes tests what a foreign producer would exercise — and it can produce
 * records a well-behaved publisher never would. S4 settled that; the shape below matches what the catalogue's
 * own test asserts it emits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PostgresServiceConnection.class, KafkaServiceConnection.class, TestKafkaTopics.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class RenameOverTheWireTest {

  private static final String RENAMED_TYPE = "com.example.samples.catalog.ProductRenamed";
  private static final String KEYBOARD = "sku-keyboard";

  @Autowired private CommandBus commandBus;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;

  @Value("${ordering.catalog-events-topic}")
  private String topic;

  private KafkaProducer<String, String> producer;

  @BeforeEach
  void setUp() {
    // Wait until the bridge's consumer actually holds the partition. Producing before that is a coin toss,
    // and a test that sometimes writes into the void passes for the wrong reason.
    listeners
        .getListenerContainers()
        .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
    jdbc.update("DELETE FROM s12_order_list");
    jdbc.update("DELETE FROM s12_order_line");
    jdbc.update("DELETE FROM s12_order");
    jdbc.update("DELETE FROM aipersimmon_inbox");
    jdbc.update("DELETE FROM s12_product_name");
    jdbc.update(
        "INSERT INTO s12_product_name (sku, name, updated_at) VALUES"
            + " ('sku-keyboard', 'Mechanical Keyboard', now()),"
            + " ('sku-mouse', 'Wireless Mouse', now())");
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
  void arenameOnTheTopicReachesTheListRow() {
    String orderId = placeOrder();
    assertThat(summaryOf(orderId)).isEqualTo("Mechanical Keyboard");

    send(renamed(KEYBOARD, "Keyboard Pro", UUID.randomUUID().toString()));

    // Awaited, unlike every assertion in ProjectionConsistencyTest. The waiting is the honest measure of the
    // difference between the two clocks.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(summaryOf(orderId)).isEqualTo("Keyboard Pro"));
    assertThat(inboxCount()).isEqualTo(1);
    // The order's own record of the purchase is untouched by anything that arrives on a topic.
    assertThat(frozenName(orderId)).isEqualTo("Mechanical Keyboard");
  }

  @Test
  void aredeliveredRenameDoesNotRecomputeAnything() {
    String orderId = placeOrder();
    String eventId = UUID.randomUUID().toString();

    send(renamed(KEYBOARD, "Keyboard Pro", eventId));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(summaryOf(orderId)).isEqualTo("Keyboard Pro"));
    Instant projectedFirst = projectedAt(orderId);

    // The steady state of an at-least-once transport, not an anomaly.
    send(renamed(KEYBOARD, "Keyboard Pro", eventId));

    // This is what the inbox buys here, and it is worth being precise about: correctness did not need it —
    // the projection recomputes whole rows, so a second pass would have produced identical content. What the
    // inbox saves is the *work*, and projected_at is how that becomes visible. Unchanged means the row was
    // never touched a second time; a redelivered rename of a popular sku would otherwise recompute every
    // affected row again.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(projectedAt(orderId)).isEqualTo(projectedFirst));
    assertThat(inboxCount()).isEqualTo(1);
  }

  private String placeOrder() {
    return commandBus.send(
        new PlaceOrder("customer-1", List.of(new PlaceOrder.Line(KEYBOARD, 1, 1500))));
  }

  private ProducerRecord<String, String> renamed(String sku, String name, String eventId) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, sku, "{\"sku\":\"%s\",\"name\":\"%s\"}".formatted(sku, name));
    header(record, "ce_specversion", "1.0");
    header(record, "ce_id", eventId);
    header(record, "ce_source", "/catalog");
    header(record, "ce_type", RENAMED_TYPE);
    header(record, "ce_dataschemaversion", "1");
    header(record, "ce_time", Instant.now().toString());
    header(record, "ce_subject", sku);
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

  private String summaryOf(String orderId) {
    return jdbc.queryForObject(
        "SELECT display_summary FROM s12_order_list WHERE order_id = ?", String.class, orderId);
  }

  private Instant projectedAt(String orderId) {
    return jdbc.queryForObject(
        "SELECT projected_at FROM s12_order_list WHERE order_id = ?", Instant.class, orderId);
  }

  private String frozenName(String orderId) {
    return jdbc.queryForObject(
        "SELECT name_at_purchase FROM s12_order_line WHERE order_id = ?", String.class, orderId);
  }

  private long inboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class);
  }
}
