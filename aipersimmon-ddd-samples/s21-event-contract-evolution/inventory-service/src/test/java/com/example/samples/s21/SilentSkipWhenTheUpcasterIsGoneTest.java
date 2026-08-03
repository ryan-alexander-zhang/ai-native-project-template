package com.example.samples.s21;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * The most dangerous fact in this sample, and the reason the {@code upcasters-removed} profile exists:
 * <strong>there are two ways to retire a revision, one loud and one silent, and the silent one looks
 * tidier.</strong>
 *
 * <ul>
 *   <li>Delete the retired <em>class</em> and its records are dead-lettered — the pair is unresolvable,
 *       so the failure is loud, countable and replayable.
 *   <li>Delete only the <em>upcaster</em>, keeping the class, and its records resolve to a class no
 *       handler is typed for. With {@code skip-locally-unhandled} on (the default) they are skipped
 *       before the inbox: no effect, no inbox row, no exception, no dead letter, no lag. The order was
 *       placed and the stock was never reserved.
 * </ul>
 *
 * <p>Removing the second file feels like the safer half of a cleanup, which is what makes it worth a
 * test. The default is left on in {@code application.yaml} rather than turned off to make this go away:
 * skipping records a service has no handler for is genuinely worth having, and the hazard is the price.
 *
 * <p>It runs in its own context — a separate container pair — because a bean set is not a property and
 * cannot be toggled per test. That cost is the reason the profile is scoped to exactly one negative
 * control and nothing else uses it.
 *
 * <p>The instrument is proved inside the test, not assumed: the same run also sends a record that
 * <em>must</em> be dead-lettered. An assertion that nothing happened is worth only as much as the proof
 * that something could have.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("upcasters-removed")
@Import({PostgresServiceConnection.class, KafkaServiceConnection.class, TestKafkaTopics.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class SilentSkipWhenTheUpcasterIsGoneTest {

  private static final String KEYBOARD = "sku-keyboard";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;

  @Value("${inventory.legacy-events-topic}")
  private String legacyTopic;

  private KafkaProducer<String, String> producer;

  @BeforeEach
  void setUp() {
    listeners
        .getListenerContainers()
        .forEach(container -> ContainerTestUtils.waitForAssignment(container, 2));
    jdbc.update("DELETE FROM aipersimmon_inbox");
    jdbc.update("UPDATE s21_stock SET available = 100, reserved = 0, version = version + 1");
    producer = TestWire.producer(kafka.getBootstrapServers());
  }

  @AfterEach
  void tearDown() {
    producer.close();
  }

  @Test
  void aRetiredRevisionWithNoUpcasterVanishesWithoutATrace() {
    String skipped = "order-skipped-" + UUID.randomUUID();
    String poison = "order-poison-" + UUID.randomUUID();

    // The record that proves the consumer is alive and reading this topic: an unregistered revision,
    // which must be dead-lettered.
    TestWire.send(
        producer,
        TestWire.record(
            legacyTopic, poison, 9, TestWire.v1Payload(poison, KEYBOARD, 1), UUID.randomUUID().toString()));
    // The record under test: a revision this service still has a class for, and can no longer carry
    // forward.
    TestWire.send(
        producer,
        TestWire.record(
            legacyTopic,
            skipped,
            1,
            TestWire.v1Payload(skipped, KEYBOARD, 2),
            UUID.randomUUID().toString()));

    assertThat(TestWire.deadLetters(kafka.getBootstrapServers(), legacyTopic, poison))
        .as("the instrument: an unregistered revision on this topic IS dead-lettered")
        .hasSize(1);

    // And the v1 record left nothing anywhere. Not an error, not a dead letter, not even an inbox row —
    // the skip happens before the inbox, because a record nothing handles has no effect to make atomic.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(KEYBOARD, "MAIN")).isZero());
    assertThat(TestWire.deadLetters(kafka.getBootstrapServers(), legacyTopic, skipped))
        .as("no dead letter either: this is the silent half of the hazard")
        .isEmpty();
    assertThat(inboxCount())
        .as("the skip is before the inbox, so not even a dedup row records that it arrived")
        .isZero();
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
