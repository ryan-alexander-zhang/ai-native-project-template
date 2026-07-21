package com.aipersimmon.ddd.messaging.kafka.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.messaging.kafka.IntegrationEventHeaders;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * End-to-end over an in-JVM embedded broker, proving per-event routing (design-00006): an
 * {@code @Externalized} event goes to <em>its</em> topic and comes back through the consumer bridge
 * to local handlers <strong>exactly once</strong> (no in-process double-delivery); events
 * externalized to different targets land on different topics; and a LOCAL event (no
 * {@code @Externalized}) is delivered in process and never touches the broker. This test lives in
 * its own package so its auto-configuration scan sees only its own fixtures.
 */
@SpringBootTest(
    classes = EventRoutingIntegrationTest.TestApp.class,
    properties = {
      "spring.application.name=routing-test",
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
      "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
      "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
      "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
      "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "aipersimmon.ddd.messaging.kafka.consumer.enabled=true"
    })
@EmbeddedKafka(
    topics = {"routing.a", "routing.a.DLT", "routing.b", "routing.b.DLT"},
    partitions = 1)
class EventRoutingIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    Handler handler() {
      return new Handler();
    }
  }

  /** Counts local deliveries per event id, per type. */
  static class Handler {
    final List<String> a = new CopyOnWriteArrayList<>();
    final List<String> b = new CopyOnWriteArrayList<>();
    final List<String> local = new CopyOnWriteArrayList<>();

    @EventListener
    void onA(EventEnvelope<ExternalEventA> e) {
      a.add(e.eventId());
    }

    @EventListener
    void onB(EventEnvelope<ExternalEventB> e) {
      b.add(e.eventId());
    }

    @EventListener
    void onLocal(EventEnvelope<LocalEvent> e) {
      local.add(e.eventId());
    }
  }

  @EventType(name = "com.example.routing.A", version = 1)
  @Externalized("routing.a")
  public static class ExternalEventA implements IntegrationEvent {
    public String value;

    @Override
    public String subject() {
      return "agg-a";
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof ExternalEventA e && Objects.equals(value, e.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }
  }

  @EventType(name = "com.example.routing.B", version = 1)
  @Externalized("routing.b")
  public static class ExternalEventB implements IntegrationEvent {
    public String value;

    @Override
    public String subject() {
      return "agg-b";
    }
  }

  @EventType(name = "com.example.routing.Local", version = 1)
  public static class LocalEvent implements IntegrationEvent {
    public String value;

    @Override
    public String subject() {
      return "agg-l";
    }
  }

  @Autowired OutboxDispatcher dispatcher;
  @Autowired EmbeddedKafkaBroker broker;
  @Autowired Handler handler;

  @Test
  void externalizedEventsRouteToTheirTopicAndComeBackExactlyOnceWhileLocalStaysInProcess()
      throws Exception {
    dispatcher.dispatch(message("a1", "com.example.routing.A", "{\"value\":\"x\"}"));
    dispatcher.dispatch(message("b1", "com.example.routing.B", "{\"value\":\"y\"}"));
    // A LOCAL event: the in-process leg republishes it synchronously, before any broker hop.
    dispatcher.dispatch(message("l1", "com.example.routing.Local", "{\"value\":\"z\"}"));

    // LOCAL delivery is synchronous and never reaches the broker.
    assertEquals(List.of("l1"), handler.local, "the local event is delivered in process");

    // EXTERNAL events come back through the bridge — wait for the async round trip.
    awaitSize(handler.a, 1, "A");
    awaitSize(handler.b, 1, "B");

    // The no-double-delivery invariant: each externalized event reaches local handlers
    // exactly once (via the bridge only, not also republished in process by the router).
    assertEquals(List.of("a1"), handler.a);
    assertEquals(List.of("b1"), handler.b);

    // Multi-topic: A landed on routing.a, B on routing.b, and neither leaked the local event.
    assertEquals(List.of("a1"), idsOnTopic("routing.a"));
    assertEquals(List.of("b1"), idsOnTopic("routing.b"));
  }

  private void awaitSize(List<String> list, int size, String label) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      if (list.size() >= size) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "timed out waiting for " + size + " delivery of " + label + " (saw " + list.size() + ")");
  }

  /** Reads all event ids currently on a topic with an independent probe consumer group. */
  private List<String> idsOnTopic(String topic) {
    Map<String, Object> props = KafkaTestUtils.consumerProps("probe-" + topic, "true", broker);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    List<String> ids = new java.util.ArrayList<>();
    try (Consumer<String, String> consumer =
        new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
            .createConsumer()) {
      consumer.subscribe(List.of(topic));
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
        for (ConsumerRecord<String, String> record : records) {
          ids.add(header(record, IntegrationEventHeaders.ID));
        }
        if (!ids.isEmpty()) {
          // one more short poll to be sure nothing else is queued
          for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
            ids.add(header(record, IntegrationEventHeaders.ID));
          }
          break;
        }
      }
    }
    return ids;
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    return record.headers().lastHeader(name) == null
        ? null
        : new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private static OutboxMessage message(String id, String type, String payload) {
    return new OutboxMessage(
        id,
        "/routing-test",
        type,
        1,
        payload,
        Instant.parse("2026-01-01T00:00:00Z"),
        "agg-" + id,
        id,
        null);
  }
}
