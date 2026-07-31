package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.EventUpcaster;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * End-to-end over an in-JVM embedded broker: a retired-revision record is upcast at the consumer
 * bridge and reaches the application as the newest revision (issue-00142). Two claims, each of
 * which failed before upcasters existed:
 *
 * <ul>
 *   <li>the single {@code EventEnvelope<Good>} listener receives a <em>v1</em> record — as a {@code
 *       Good} payload whose envelope says version 2, the revision actually carried;
 *   <li>the skip-locally-unhandled scan does not drop the v1 record even though no listener is
 *       typed for {@code GoodV1}: what matters is the revision the chain delivers, not the one on
 *       the wire. This is the interplay that would silently eat every old-revision record the
 *       moment a consumer collapsed its per-revision listeners into one.
 * </ul>
 */
@SpringBootTest(
    classes = KafkaUpcastIntegrationTest.TestApp.class,
    properties = {
      "spring.application.name=kupc-test",
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
      "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
      "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
      "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
      "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "aipersimmon.ddd.messaging.kafka.consumer.enabled=true",
      // The scan-based skip must stay ON: its interplay with upcasting is half of this test.
      "aipersimmon.ddd.messaging.kafka.consumer.skip-locally-unhandled=true",
    })
@EmbeddedKafka(
    topics = {"upc-events"},
    partitions = 1)
class KafkaUpcastIntegrationTest {

  private static final String TOPIC = "upc-events";
  private static final String NAME = "com.example.Good";

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    IntegrationEventCatalog integrationEventCatalog() {
      return new RegistryIntegrationEventCatalog(
          Map.of(new Key(NAME, 1), GoodV1.class, new Key(NAME, 2), Good.class));
    }

    @Bean
    ExternalizedRoutes externalizedRoutes() {
      return new ExternalizedRoutes(Map.of(new Key(NAME, 2), TOPIC));
    }

    @Bean
    Handler handler() {
      return new Handler();
    }

    @Bean
    EventUpcaster<GoodV1, Good> goodV1Upcaster() {
      // A concrete (here anonymous) class, not a lambda: the chain reads the two revisions from
      // the instance's generic supertype, and a lambda erases it.
      return new EventUpcaster<>() {
        @Override
        public Good upcast(GoodV1 v1) {
          Good good = new Good();
          good.value = v1.value;
          // v2's addition stays absent: the old revision never carried one and none can be
          // invented — the successor contract tolerates it.
          good.extra = null;
          return good;
        }
      };
    }
  }

  /** The one listener: typed for the newest revision only. */
  static class Handler {
    final List<EventEnvelope<Good>> received = new CopyOnWriteArrayList<>();

    @EventListener
    void on(EventEnvelope<Good> envelope) {
      received.add(envelope);
    }
  }

  /** JavaBean events so Jackson maps by field without needing -parameters. */
  @EventType(name = NAME, version = 1)
  public static class GoodV1 implements IntegrationEvent {
    public String value;

    public GoodV1() {}
  }

  @EventType(name = NAME, version = 2)
  @Externalized(TOPIC)
  public static class Good implements IntegrationEvent {
    public String value;
    public String extra;

    public Good() {}

    @Override
    public boolean equals(Object o) {
      return o instanceof Good g
          && Objects.equals(value, g.value)
          && Objects.equals(extra, g.extra);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, extra);
    }
  }

  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired Handler handler;

  @Test
  void aRetiredRevisionRecordReachesTheOneListenerAsTheNewestRevision() throws Exception {
    kafkaTemplate.send(v1Record("g1", "hello")).get();

    awaitReceived();

    EventEnvelope<Good> envelope = handler.received.get(0);
    assertEquals("hello", envelope.payload().value, "the v1 fields must survive the upcast");
    assertNull(envelope.payload().extra, "what v1 never carried, the upcast must not invent");
    assertEquals(
        2,
        envelope.version(),
        "the envelope must describe the payload actually delivered, not the wire's revision");
    assertEquals("g1", envelope.eventId(), "identity is the wire's — upcasting mints nothing");
  }

  private void awaitReceived() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      if (!handler.received.isEmpty()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "timed out: the v1 record never reached the v2-typed listener — either the upcast did not"
            + " run or the skip-locally-unhandled scan dropped the retired revision");
  }

  private ProducerRecord<String, String> v1Record(String id, String value) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(TOPIC, id, "{\"value\":\"" + value + "\"}");
    addHeader(record, IntegrationEventHeaders.ID, id);
    addHeader(record, IntegrationEventHeaders.TYPE, NAME);
    addHeader(record, IntegrationEventHeaders.SOURCE, "/upc");
    addHeader(
        record, IntegrationEventHeaders.SPEC_VERSION, IntegrationEventHeaders.SPEC_VERSION_VALUE);
    addHeader(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION, "1");
    return record;
  }

  private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
