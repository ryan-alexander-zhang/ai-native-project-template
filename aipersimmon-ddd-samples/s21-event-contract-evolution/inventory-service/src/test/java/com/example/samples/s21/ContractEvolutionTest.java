package com.example.samples.s21;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.messaging.kafka.ExternalizedRoutes;
import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s21.inventory.api.OrderPlaced;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
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
 * Three revisions arriving at one consumer, and the handful of facts that decide whether a migration is
 * survivable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  ReceivedEnvelopes.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ContractEvolutionTest {

  private static final String KEYBOARD = "sku-keyboard";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @Autowired private ExternalizedRoutes routes;
  @Autowired private ReceivedEnvelopes.Recorder recorder;

  @Value("${inventory.ordering-events-topic}")
  private String topic;

  @Value("${inventory.legacy-events-topic}")
  private String legacyTopic;

  private KafkaProducer<String, String> producer;

  @BeforeEach
  void setUp() {
    // Two topics, so two partitions to be assigned. Waiting for one would let a test produce to the
    // topic that had not been assigned yet — a coin toss, and one that turns every "nothing happened"
    // assertion in this class into a statement about a broken instrument.
    listeners
        .getListenerContainers()
        .forEach(container -> ContainerTestUtils.waitForAssignment(container, 2));
    jdbc.update("DELETE FROM aipersimmon_inbox");
    jdbc.update("UPDATE s21_stock SET available = 100, reserved = 0, version = version + 1");
    recorder.clear();
    producer = TestWire.producer(kafka.getBootstrapServers());
  }

  @AfterEach
  void tearDown() {
    producer.close();
  }

  @Test
  void av1RecordRidesTheWholeChainToTheOneListener() {
    String orderId = "order-" + UUID.randomUUID();

    TestWire.send(
        producer,
        TestWire.record(
            legacyTopic, orderId, 1, TestWire.v1Payload(orderId, KEYBOARD, 2), eventId()));

    // Two hops (v1 → v2 → v3) and a listener typed only for v3. Without the chain this record would
    // have been skipped: no handler is typed for the retired revision, and the skip check is on by
    // default. The library asks the chain what revision the payload will *become* before deciding.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(KEYBOARD, "MAIN")).isEqualTo(2));
    assertThat(inboxCount()).isEqualTo(1);
  }

  @Test
  void av2RecordRidesOneHopAndLandsInTheSamePlace() {
    String orderId = "order-" + UUID.randomUUID();

    TestWire.send(
        producer,
        TestWire.record(topic, orderId, 2, TestWire.v2Payload(orderId, KEYBOARD, 3), eventId()));

    // The chain is entered wherever the record's revision sits, not at its start.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(KEYBOARD, "MAIN")).isEqualTo(3));
  }

  @Test
  void av3RecordArrivesUnchangedAndNamesItsOwnWarehouse() {
    String orderId = "order-" + UUID.randomUUID();

    TestWire.send(
        producer,
        TestWire.record(
            topic, orderId, 3, TestWire.v3Payload(orderId, KEYBOARD, 4, "EU"), eventId()));

    // The current revision passes through untouched, and its added field does something observable —
    // which is what makes the next test's claim about absence worth making.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(KEYBOARD, "EU")).isEqualTo(4));
    assertThat(reserved(KEYBOARD, "MAIN")).isZero();
  }

  @Test
  void theUpcastDoesNotInventWhatTheRetiredRevisionNeverCarried() {
    String orderId = "order-" + UUID.randomUUID();
    String eventId = eventId();

    TestWire.send(
        producer,
        TestWire.record(legacyTopic, orderId, 1, TestWire.v1Payload(orderId, KEYBOARD, 1), eventId));

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(recorder.forOrder(orderId)).hasSize(1));
    EventEnvelope<OrderPlaced> envelope = recorder.forOrder(orderId).get(0);

    // The iron law. v1 never carried a warehouse, so the field arrives absent — not defaulted, not
    // guessed. A substituted value would be indistinguishable downstream from one the publisher
    // actually sent, and the "obvious" default is exactly the kind that is obvious until it is wrong.
    //
    // This assertion is on the PAYLOAD rather than on the stock, and the negative control is why:
    // making the upcaster fabricate "MAIN" turns this one assertion red and leaves every other test in
    // this class green — because the fabricated value happens to match the rule the adapter would have
    // applied anyway, so the effect is identical. A contract violation whose effect is currently
    // indistinguishable is still a violation; it becomes visible the day the rule changes, in data
    // written years earlier. Only reading the payload catches it.
    assertThat(envelope.payload().warehouseCode())
        .as("what the retired revision never carried must arrive absent")
        .isNull();
    // What survived the hops: the data, and the identity. Upcasting mints nothing.
    assertThat(envelope.payload().lines()).hasSize(1);
    assertThat(envelope.eventId()).isEqualTo(eventId);
    // The envelope describes the payload it is carrying, not the bytes' revision. The wire's original
    // version stays on the Kafka record's headers, where the application cannot reach it
    // (KafkaIntegrationEventListener:220-224) — deliberately, because a consumer that branches on the
    // wire revision has undone the normalisation it just paid for.
    assertThat(envelope.version())
        .as("after normalisation the version is the payload's, not the wire's")
        .isEqualTo(3);
    // And the effect the listener's own rule produced: absence means MAIN, decided in the adapter.
    assertThat(reserved(KEYBOARD, "MAIN")).isEqualTo(1);
  }

  @Test
  void arevisionThisConsumerHasNotAdoptedIsDeadLetteredRatherThanGuessedAt() {
    String orderId = "order-v4-" + UUID.randomUUID();

    TestWire.send(
        producer,
        TestWire.record(
            topic, orderId, 4, TestWire.v3Payload(orderId, KEYBOARD, 5, "EU"), eventId()));

    // This is what a publisher-first deploy looks like from the consuming side: a revision it has no
    // class for, dead-lettered on arrival. Resolution is the exact (name, version) pair and there is
    // no fallback — so the outage is loud and bounded instead of a silent misreading of the payload at
    // the wrong revision. Hence the deploy order: CONSUMERS FIRST. A consumer that already knows v4
    // costs nothing while v3 is still being sent; a publisher that ships v4 first costs one dead letter
    // per order.
    assertThat(TestWire.deadLetters(kafka.getBootstrapServers(), topic, orderId)).hasSize(1);
    assertThat(reserved(KEYBOARD, "EU")).isZero();
    assertThat(reserved(KEYBOARD, "MAIN")).isZero();
  }

  @Test
  void dualPublishingOneFactAppliesItTwice() {
    String orderId = "order-" + UUID.randomUUID();

    // The same fact, published at two revisions "so old consumers keep working". Two records, two
    // event ids, two topics.
    TestWire.send(
        producer,
        TestWire.record(legacyTopic, orderId, 1, TestWire.v1Payload(orderId, KEYBOARD, 2), eventId()));
    TestWire.send(
        producer,
        TestWire.record(
            topic, orderId, 3, TestWire.v3Payload(orderId, KEYBOARD, 2, "MAIN"), eventId()));

    // Reserved twice. The inbox cannot help: dedup is by (source, id) and these are two distinct
    // events, which is what they are — the duplication is in the publishing decision, not the
    // transport. Every consumer that still declares the retired revision is affected, and those are
    // precisely the consumers dual publishing was meant to protect.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(KEYBOARD, "MAIN")).isEqualTo(4));
    assertThat(inboxCount()).isEqualTo(2);
  }

  @Test
  void anOptionalAdditionInsideOneRevisionNeedsNoBump() {
    String orderId = "order-" + UUID.randomUUID();
    String payload =
        "{\"orderId\":\"%s\",\"lines\":[{\"sku\":\"%s\",\"quantity\":2}],\"warehouseCode\":\"EU\",\"promisedAt\":\"2026-08-03T00:00:00Z\"}"
            .formatted(orderId, KEYBOARD);

    // A publisher that added an optional field and did NOT move the version. This consumer has not
    // adopted it and does not model it.
    TestWire.send(producer, TestWire.record(topic, orderId, 3, payload, eventId()));

    // Processed normally: an unknown JSON property is ignored, so a purely additive optional field is
    // the one change that costs no revision, no upcaster and no coordination. That is the path to
    // prefer — and the reason v2's restructuring could not take it is that renaming or splitting a
    // field is not additive, however optional the new one is.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(KEYBOARD, "EU")).isEqualTo(2));
  }

  @Test
  void theSubscriptionIsTheUnionOfTheTopicsTheDeclaredRevisionsName() {
    // Nothing configures "read both". The routing table is keyed by (name, version), so a revision
    // that names a different topic adds that topic to the subscription — which is how a topic move is
    // survived, and why dropping @Externalized from a retired revision stops it being read in silence.
    assertThat(routes.topics()).containsExactly(topic, legacyTopic);
  }

  private static String eventId() {
    return UUID.randomUUID().toString();
  }

  private int reserved(String sku, String warehouse) {
    return jdbc.queryForObject(
        "SELECT reserved FROM s21_stock WHERE sku = ? AND warehouse = ?",
        Integer.class,
        sku,
        warehouse);
  }

  private long inboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class);
  }
}
