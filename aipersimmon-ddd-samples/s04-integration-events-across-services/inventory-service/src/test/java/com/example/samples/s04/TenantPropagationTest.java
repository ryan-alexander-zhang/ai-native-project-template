package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import org.apache.kafka.clients.producer.KafkaProducer;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;

/**
 * S13, consuming side: the tenant crosses the broker as a CloudEvents attribute, and the message
 * consumer is the trusted boundary that binds it.
 *
 * <p>Annotation set copied verbatim from {@code InboxConsumptionTest} so the classes share one context
 * and one container pair.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  Probes.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class TenantPropagationTest {

  private static final String KEYBOARD = "sku-keyboard";
  private static final String ACME = "acme";
  private static final String GLOBEX = "globex";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TestRestTemplate http;
  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @Autowired private Probes.Recorder recorder;

  @Value("${inventory.ordering-events-topic}")
  private String topic;

  private KafkaProducer<String, String> producer;

  @BeforeEach
  void setUp() {
    listeners
        .getListenerContainers()
        .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
    jdbc.update("DELETE FROM aipersimmon_inbox");
    jdbc.update("UPDATE s04_stock SET available = 100, reserved = 0, version = version + 1");
    recorder.clear();
    producer = WireRecords.producer(kafka.getBootstrapServers());
  }

  @AfterEach
  void tearDown() {
    producer.close();
  }

  @Test
  void thetenantOnTheRecordDecidesWhichBucketMoves() {
    String orderId = "order-" + UUID.randomUUID();

    WireRecords.send(producer, WireRecords.order(topic, orderId, KEYBOARD, 2, GLOBEX));

    // The bridge binds ce_tenantid for the whole transaction — before the inbox row, before the
    // command, before the aggregate — so every statement underneath is scoped without anything
    // downstream taking a tenant parameter.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(GLOBEX)).isEqualTo(2));
    assertThat(reserved(ACME)).as("the other tenant's stock is untouched").isZero();
    // Even the framework's own dedup row records whose message it was.
    assertThat(jdbc.queryForObject("SELECT tenant_id FROM aipersimmon_inbox", String.class))
        .isEqualTo(GLOBEX);
  }

  @Test
  void thecommandInheritedTheTenantFromTheEnvelopeAndNotFromAnyRequest() {
    String orderId = "order-" + UUID.randomUUID();

    WireRecords.send(producer, WireRecords.order(topic, orderId, KEYBOARD, 1, GLOBEX));

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(recorder.all()).hasSize(1));
    Probes.Handled handled = recorder.only();
    // There is no HTTP request anywhere near this, and no thread-local left over from one: this
    // service's other trusted boundary is the consumer, and the attribute on the record is the whole
    // input. That is the answer to "how does a tenant reach work that has no request".
    assertThat(handled.context().tenantId().value()).isEqualTo(GLOBEX);
    assertThat(handled.ambientTenant()).isEqualTo(GLOBEX);
  }

  @Test
  void arecordCarryingNoTenantIsRejectedRatherThanAttributed() {
    String orderId = "order-tenantless-" + UUID.randomUUID();

    WireRecords.send(
        producer,
        WireRecords.order(
            topic, orderId, KEYBOARD, 3, null, UUID.randomUUID().toString(), null, "/ordering"));

    // A permanent failure, dead-lettered: with tenancy on there is no safe default. Attributing the
    // event to the __root__ sentinel would file another tenant's data in the bucket that, in a
    // deployment migrated from single-tenant, holds the pre-migration production rows. Producers that
    // predate tenancy are therefore only acceptable while tenancy is off.
    assertThat(awaitDeadLetter(orderId)).hasSize(1);
    assertThat(reserved(ACME)).isZero();
    assertThat(reserved(GLOBEX)).isZero();
  }

  @Test
  void thehttpReadIsScopedToTheCallersTenantWithoutAskingForIt() {
    String orderId = "order-" + UUID.randomUUID();
    WireRecords.send(producer, WireRecords.order(topic, orderId, KEYBOARD, 4, GLOBEX));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(GLOBEX)).isEqualTo(4));

    // One URL, one controller method, no tenant parameter — two answers. The read side has no
    // interceptor chain of its own, so this is not a query contract being enforced: it is the same SQL
    // rewriting the write path gets, which is exactly why the read cannot forget.
    assertThat(JsonPath.<Integer>read(stock(GLOBEX).getBody(), "$.reserved")).isEqualTo(4);
    assertThat(JsonPath.<Integer>read(stock(ACME).getBody(), "$.reserved")).isZero();
  }

  @Test
  void thededupKeyDeliberatelyExcludesTheTenant() {
    String eventId = UUID.randomUUID().toString();
    String acmeOrder = "order-acme-" + UUID.randomUUID();
    String globexOrder = "order-globex-" + UUID.randomUUID();

    WireRecords.send(
        producer,
        WireRecords.order(topic, acmeOrder, KEYBOARD, 2, ACME, eventId, null, "/ordering"));
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(reserved(ACME)).isEqualTo(2));
    // The same source, the same id, a different tenant.
    WireRecords.send(
        producer,
        WireRecords.order(topic, globexOrder, KEYBOARD, 2, GLOBEX, eventId, null, "/ordering"));

    // Dropped as a duplicate. The inbox key is (consumer, source, message_key) and the tenant is a
    // stamped data column, which the library says out loud — so this is a premise, not a defect: a
    // producer must mint ids unique per SOURCE, not per (source, tenant). It holds trivially for UUIDs
    // and breaks the day a multi-tenant producer numbers its events per tenant under one ce_source.
    // The fix if you must shard ids that way is to shard the source with them.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(reserved(GLOBEX)).isZero());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class)).isEqualTo(1);
  }

  private ResponseEntity<String> stock(String tenant) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", tenant);
    ResponseEntity<String> response =
        http.exchange(
            "/stock/" + KEYBOARD, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response;
  }

  private int reserved(String tenant) {
    return jdbc.queryForObject(
        "SELECT reserved FROM s04_stock WHERE tenant_id = ? AND sku = ?",
        Integer.class,
        tenant,
        KEYBOARD);
  }

  private List<ConsumerRecord<String, String>> awaitDeadLetter(String orderId) {
    String dlt = topic + ".DLT";
    List<ConsumerRecord<String, String>> collected = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG,
                "tenant-dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false"),
            new StringDeserializer(),
            new StringDeserializer())) {
      consumer.subscribe(List.of(dlt));
      for (int attempt = 0; attempt < 40 && collected.isEmpty(); attempt++) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
        polled
            .records(dlt)
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
}
