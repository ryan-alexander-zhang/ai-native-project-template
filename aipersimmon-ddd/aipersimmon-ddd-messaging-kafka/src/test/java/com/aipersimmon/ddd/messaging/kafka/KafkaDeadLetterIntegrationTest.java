package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * End-to-end over an in-JVM embedded broker (no Docker), proving the consumer's
 * dead-letter contract that the unit tests can only approximate: a poison record
 * (an unknown {@code (type, version)}) is republished to {@code <topic>.DLT} instead of
 * being silently skipped, the consumer advances past it and keeps consuming (a later
 * good record is handled), and the inbox is not corrupted — the poison's inbox mark is
 * rolled back with its failed transaction while a good record's mark is committed.
 */
@SpringBootTest(
        classes = KafkaDeadLetterIntegrationTest.TestApp.class,
        properties = {
                "spring.application.name=kdlt-test",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "aipersimmon.ddd.messaging.kafka.topic=it-events",
                "aipersimmon.ddd.messaging.kafka.consumer.enabled=true",
                "aipersimmon.ddd.messaging.kafka.consumer.retry.max-retries=2",
                "aipersimmon.ddd.messaging.kafka.consumer.retry.initial-interval-ms=50",
                "aipersimmon.ddd.messaging.kafka.consumer.retry.max-interval-ms=50"})
@EmbeddedKafka(topics = {"it-events", "it-events.DLT"}, partitions = 1)
class KafkaDeadLetterIntegrationTest {

    private static final String TOPIC = "it-events";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        IntegrationEventCatalog integrationEventCatalog() {
            return new RegistryIntegrationEventCatalog(Map.of(new Key("com.example.Good", 1), GoodEvent.class));
        }

        /**
         * Pin the routing to just {@code it-events} so the consumer bridge subscribes to
         * exactly the topic this test drives, independent of any other externalized fixture
         * on the test classpath.
         */
        @Bean
        ExternalizedRoutes externalizedRoutes() {
            return new ExternalizedRoutes(Map.of(new Key("com.example.Good", 1), TOPIC));
        }

        @Bean
        Handler handler() {
            return new Handler();
        }
    }

    /** Receives the republished envelope for a good event; poison never reaches here. */
    static class Handler {
        final List<String> handled = new CopyOnWriteArrayList<>();

        @EventListener
        void on(EventEnvelope<GoodEvent> envelope) {
            handled.add(envelope.eventId());
        }
    }

    /** A JavaBean event so Jackson maps it by field without needing -parameters. */
    @EventType(name = "com.example.Good", version = 1)
    @Externalized(TOPIC)
    public static class GoodEvent implements IntegrationEvent {
        public String value;

        public GoodEvent() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GoodEvent e && Objects.equals(value, e.value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Handler handler;

    @Test
    void aPoisonRecordIsDeadLetteredWhileTheConsumerAdvancesAndTheInboxStaysClean() throws Exception {
        // A well-formed record whose (type, version) has no local class -> permanent failure.
        kafkaTemplate.send(poison("p1")).get();
        // A good record after it on the same partition.
        kafkaTemplate.send(good("g1", "hello")).get();

        // (4) the consumer advanced past the poison and handled the later good record.
        awaitHandled("g1");

        // (1) the poison was republished to <topic>.DLT, not silently skipped.
        ConsumerRecord<String, String> dead = readSingleDeadLetter();
        assertNotNull(dead, "the poison must land on the DLT");
        assertEquals("p1", headerValue(dead, IntegrationEventHeaders.ID),
                "the DLT carries the original poison record");

        // (3) the inbox is not corrupted: the poison's mark rolled back with its failed
        // transaction; the good record's mark is committed.
        assertEquals(Integer.valueOf(0), inboxCount("p1"),
                "the poison's inbox mark is rolled back, so a fixed redelivery could reprocess");
        assertEquals(Integer.valueOf(1), inboxCount("g1"), "the good record is recorded once");
        assertEquals(1, handler.handled.stream().filter("g1"::equals).count(), "handled exactly once");
    }

    private Integer inboxCount(String messageKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_inbox WHERE message_key = ?", Integer.class, messageKey);
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

    private ConsumerRecord<String, String> readSingleDeadLetter() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-probe", "true", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    return record;
                }
            }
            return null;
        }
    }

    private ProducerRecord<String, String> poison(String id) {
        return record(id, "com.example.Unknown", "{}");
    }

    private ProducerRecord<String, String> good(String id, String value) {
        return record(id, "com.example.Good", "{\"value\":\"" + value + "\"}");
    }

    private ProducerRecord<String, String> record(String id, String type, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, id, payload);
        addHeader(record, IntegrationEventHeaders.ID, id);
        addHeader(record, IntegrationEventHeaders.TYPE, type);
        addHeader(record, IntegrationEventHeaders.SOURCE, "/it");
        addHeader(record, IntegrationEventHeaders.SPEC_VERSION, IntegrationEventHeaders.SPEC_VERSION_VALUE);
        addHeader(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION, "1");
        return record;
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        return record.headers().lastHeader(name) == null
                ? null : new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
