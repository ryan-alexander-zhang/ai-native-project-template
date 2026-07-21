package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * End-to-end over an in-JVM embedded broker (no Docker), proving the systemic-failure contract of
 * issue-00047: a handler that fails with a {@link org.springframework.dao.DataAccessException} (a
 * simulated database outage) for the first few deliveries and then recovers is retried until it
 * succeeds and is <strong>never</strong> dead-lettered — the opposite of the poison contract. This
 * is what keeps a transient infrastructure outage from flooding the DLT with healthy messages and
 * skipping past events (which would break per-aggregate order).
 */
@SpringBootTest(
    classes = KafkaSystemicFailureIntegrationTest.TestApp.class,
    properties = {
      "spring.application.name=ksf-test",
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
      "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
      "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
      "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
      "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "aipersimmon.ddd.messaging.kafka.topic=ksf-events",
      "aipersimmon.ddd.messaging.kafka.consumer.enabled=true",
      // Short so the test does not wait the 10s production default between retries.
      "aipersimmon.ddd.messaging.kafka.consumer.systemic-backoff-interval-ms=200"
    })
@EmbeddedKafka(
    topics = {"ksf-events", "ksf-events.DLT"},
    partitions = 1)
class KafkaSystemicFailureIntegrationTest {

  private static final String TOPIC = "ksf-events";

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    IntegrationEventCatalog integrationEventCatalog() {
      return new RegistryIntegrationEventCatalog(
          Map.of(new Key("com.example.Good", 1), GoodEvent.class));
    }

    @Bean
    ExternalizedRoutes externalizedRoutes() {
      return new ExternalizedRoutes(Map.of(new Key("com.example.Good", 1), TOPIC));
    }

    @Bean
    FlakyHandler handler() {
      return new FlakyHandler(2); // fail twice (simulated outage), then recover
    }
  }

  /** Throws a DataAccessException for the first {@code failuresBeforeSuccess} deliveries. */
  static class FlakyHandler {
    private final int failuresBeforeSuccess;
    private final AtomicInteger attempts = new AtomicInteger();
    final List<String> handled = new CopyOnWriteArrayList<>();

    FlakyHandler(int failuresBeforeSuccess) {
      this.failuresBeforeSuccess = failuresBeforeSuccess;
    }

    @EventListener
    void on(EventEnvelope<GoodEvent> envelope) {
      if (attempts.getAndIncrement() < failuresBeforeSuccess) {
        throw new DataAccessResourceFailureException("simulated database outage");
      }
      handled.add(envelope.eventId());
    }
  }

  /** A JavaBean event so Jackson maps it by field without needing -parameters. */
  @EventType(name = "com.example.Good", version = 1)
  @Externalized(TOPIC)
  public static class GoodEvent implements IntegrationEvent {
    public String value;

    public GoodEvent() {}

    @Override
    public boolean equals(Object o) {
      return o instanceof GoodEvent e && Objects.equals(value, e.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }
  }

  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired EmbeddedKafkaBroker broker;
  @Autowired FlakyHandler handler;

  @Test
  void aSystemicFailureIsRetriedUntilRecoveryAndNeverDeadLettered() throws Exception {
    kafkaTemplate.send(good("g1", "hello")).get();

    // The handler fails twice (systemic), is retried, and succeeds on the third delivery.
    awaitHandled("g1");
    assertEquals(
        1,
        handler.handled.stream().filter("g1"::equals).count(),
        "the event is handled exactly once after recovery");

    // It must never have been dead-lettered — a systemic failure is not a poison message.
    assertNull(
        readAnyDeadLetter(), "a systemic (infrastructure) failure must not be dead-lettered");
  }

  private void awaitHandled(String eventId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      if (handler.handled.contains(eventId)) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("timed out waiting for the consumer to handle " + eventId);
  }

  /**
   * Polls {@code <topic>.DLT} for a short window; returns the first dead letter, or null if none.
   */
  private ConsumerRecord<String, String> readAnyDeadLetter() {
    Map<String, Object> props = KafkaTestUtils.consumerProps("ksf-dlt-probe", "true", broker);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    try (Consumer<String, String> consumer =
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
            .createConsumer()) {
      consumer.subscribe(List.of(TOPIC + ".DLT"));
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          return record;
        }
      }
      return null;
    }
  }

  private ProducerRecord<String, String> good(String id, String value) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(TOPIC, id, "{\"value\":\"" + value + "\"}");
    addHeader(record, IntegrationEventHeaders.ID, id);
    addHeader(record, IntegrationEventHeaders.TYPE, "com.example.Good");
    addHeader(record, IntegrationEventHeaders.SOURCE, "/ksf");
    addHeader(
        record, IntegrationEventHeaders.SPEC_VERSION, IntegrationEventHeaders.SPEC_VERSION_VALUE);
    addHeader(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION, "1");
    return record;
  }

  private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
