package com.example.samples.s05;

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
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;

/**
 * The ERP's messages, written the way the ERP writes them.
 *
 * <p>Every record here is raw JSON with the upstream's field names and no CloudEvents attributes at all —
 * which is the whole premise of S5, and the reason the framework's consumer bridge is not in this service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  FailOnceForSku.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ErpIngestionTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TestRestTemplate http;
  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;

  @Value("${catalog.erp-topic}")
  private String topic;

  private KafkaProducer<String, String> erp;

  @BeforeEach
  void setUp() {
    // The consumer must hold the partition before anything is produced, or a record written during the
    // group's join may or may not be seen — and every "nothing happened" assertion below would be a
    // statement about a broken instrument rather than about the code.
    listeners
        .getListenerContainers()
        .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
    jdbc.update("DELETE FROM s05_product");
    jdbc.update("DELETE FROM aipersimmon_inbox");
    erp =
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
    erp.close();
  }

  @Test
  void aforeignMessageBecomesACommandAndMirrorsTheProduct() {
    String sku = sku();

    send(productChanged(sku, 1, "Mechanical keyboard", "9.99", "EUR"));

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(revisionOf(sku)).isEqualTo(1));
    assertThat(nameOf(sku)).isEqualTo("Mechanical keyboard");
    // "9.99" became 999 at the boundary. The domain never sees a decimal string, and never sees the
    // currency either — that check happened once, in the translation.
    assertThat(priceOf(sku)).isEqualTo(999);
    // And the mirror can say which upstream version it is holding, which is the first question anyone
    // debugging a stale mirror asks.
    ResponseEntity<String> response = http.getForEntity("/products/" + sku, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<Integer>read(response.getBody(), "$.upstreamRevision")).isEqualTo(1);
  }

  @Test
  void alateChangeDoesNotOverwriteANewerOne() {
    String sku = sku();

    send(productChanged(sku, 7, "Current name", "20.00", "EUR"));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(revisionOf(sku)).isEqualTo(7));
    // The message that took the slow path through the ERP's own plumbing, arriving after the one that
    // overtook it. Kafka's per-partition order does not help: it was published later.
    send(productChanged(sku, 5, "Stale name", "10.00", "EUR"));

    // Nothing to wait for — the assertion is that nothing changes. Give the consumer time to have
    // processed it, then check the newer value survived.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(revisionOf(sku)).isEqualTo(7));
    assertThat(nameOf(sku)).isEqualTo("Current name");
    assertThat(priceOf(sku)).isEqualTo(2000);
  }

  @Test
  void aduplicateAbsoluteChangeNeedsNoDedupKeyAtAll() {
    String sku = sku();

    send(productChanged(sku, 3, "Keyboard", "15.00", "EUR"));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(revisionOf(sku)).isEqualTo(3));
    send(productChanged(sku, 3, "Keyboard", "15.00", "EUR"));

    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(priceOf(sku)).isEqualTo(1500));
    // The point of the assertion: NO inbox row was written for either delivery. The revision comparison
    // made the second one a no-op by content, so a dedup key would have been a second mechanism guarding
    // a property the first already guarantees.
    assertThat(inboxCount()).isZero();
  }

  @Test
  void sharingATimestampDoesNotMakeTwoChangesUnorderable() {
    String sku = sku();
    String sameInstant = "2026-08-03T10:15:30.000+02:00";

    send(productChanged(sku, 4, "Newer", "40.00", "EUR", sameInstant));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(revisionOf(sku)).isEqualTo(4));
    send(productChanged(sku, 3, "Older", "30.00", "EUR", sameInstant));

    // Both changes claim the same millisecond, so a timestamp comparison would have had to pick one
    // arbitrarily — and "arbitrarily" means "by arrival", which is the thing being defended against. The
    // revision is a total order per product and needs no clock to be trusted. Where an upstream offers
    // only a timestamp, this case is simply unorderable, and that is worth knowing before choosing it.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(nameOf(sku)).isEqualTo("Newer"));
  }

  @Test
  void aredeliveredRelativeChangeIsAppliedOnce() {
    String sku = sku();
    send(productChanged(sku, 1, "Keyboard", "100.00", "EUR"));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(priceOf(sku)).isEqualTo(10000));
    String messageId = "erp-msg-" + UUID.randomUUID();

    send(priceReduced(sku, 10, messageId));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(priceOf(sku)).isEqualTo(9000));
    // The same message again. There is no content to compare: "reduce by 10%" is as true the second time.
    send(priceReduced(sku, 10, messageId));

    await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(priceOf(sku)).isEqualTo(9000));
    // One row, written in the same transaction as the price change — so a rollback would have taken it
    // with it, and the retry would have applied the reduction rather than skipping it.
    assertThat(inboxCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT source FROM aipersimmon_inbox", String.class))
        .isEqualTo("erp");
  }

  @Test
  void arelativeChangeWithoutAnUpstreamIdIsRefusedRatherThanGuessedAt() {
    String sku = sku();
    send(productChanged(sku, 1, "Keyboard", "100.00", "EUR"));
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(priceOf(sku)).isEqualTo(10000));

    send(record(sku, priceReducedJson(sku, 10, null)));

    // Dead-lettered, and the price untouched. This is the sample's sharpest refusal: a relative effect
    // with no producer id cannot be deduplicated, and every local substitute (a payload hash, the topic
    // offset, an id minted on arrival) fails precisely on the replay it was supposed to protect. Making
    // the message absolute is a contract change worth asking for; inventing identity is not.
    assertThat(awaitDeadLetter(sku)).hasSize(1);
    assertThat(priceOf(sku)).isEqualTo(10000);
  }

  @Test
  void anUnparseablePayloadIsDeadLetteredAtOnce() {
    String sku = sku();

    send(record(sku, "{this is not json"));

    assertThat(awaitDeadLetter(sku)).hasSize(1);
    assertThat(exists(sku)).isFalse();
  }

  @Test
  void anUnknownEventKindIsDeadLetteredRatherThanSkipped() {
    String sku = sku();

    send(record(sku, "{\"event_kind\":\"PRODUCT_DISCONTINUED\",\"sku_id\":\"" + sku + "\"}"));

    // Not silently dropped: an unknown kind may be traffic this consumer was meant to grow into, and a
    // dropped message leaves nothing to discover it by.
    assertThat(awaitDeadLetter(sku)).hasSize(1);
    assertThat(exists(sku)).isFalse();
  }

  @Test
  void achangeWithoutARevisionIsRefused() {
    String sku = sku();

    send(
        record(
            sku,
            "{\"event_kind\":\"PRODUCT_CHANGED\",\"sku_id\":\""
                + sku
                + "\",\"display_name\":\"Keyboard\",\"price\":{\"amount\":\"1.00\",\"currency\":\"EUR\"}}"));

    // Without an ordering token there is no way to tell a late message from a new one, so accepting it
    // would mean accepting that some overtaken message will eventually overwrite the truth.
    assertThat(awaitDeadLetter(sku)).hasSize(1);
    assertThat(exists(sku)).isFalse();
  }

  @Test
  void apriceInACurrencyThisCatalogDoesNotMirrorIsRefused() {
    String sku = sku();

    send(productChanged(sku, 1, "Keyboard", "9.99", "USD"));

    // A mirror is not a rubber stamp. Accepting the number and dropping the currency is how a catalog
    // ends up with two currencies in one column and no way to tell which is which.
    assertThat(awaitDeadLetter(sku)).hasSize(1);
    assertThat(exists(sku)).isFalse();
  }

  @Test
  void asubCentPriceIsRefusedRatherThanRounded() {
    String sku = sku();

    send(productChanged(sku, 1, "Keyboard", "9.999", "EUR"));

    // Rounding somebody else's money silently is where a mirror stops being one.
    assertThat(awaitDeadLetter(sku)).hasSize(1);
    assertThat(exists(sku)).isFalse();
  }

  @Test
  void anExtraFieldTheUpstreamAddedIsIgnored() {
    String sku = sku();

    send(
        record(
            sku,
            "{\"event_kind\":\"PRODUCT_CHANGED\",\"sku_id\":\""
                + sku
                + "\",\"display_name\":\"Keyboard\",\"rev\":2,"
                + "\"price\":{\"amount\":\"5.00\",\"currency\":\"EUR\"},"
                + "\"warehouse_hints\":[{\"code\":\"EU\"}],\"legacy_flag\":true}"));

    // An upstream that adds a field must not break a consumer that does not read it. The mirror image —
    // a field this translation needs going missing — surfaces as a refusal, which is the right asymmetry.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(priceOf(sku)).isEqualTo(500));
  }

  @Test
  void atransientDatabaseFailureIsRetriedRatherThanDeadLettered() {
    String sku = FailOnceForSku.POISON_SKU;
    FailOnceForSku.attempts.set(0);

    send(productChanged(sku, 1, "Keyboard", "12.34", "EUR"));

    // The first attempt rolled back inside the transaction; the container retried after its backoff and
    // the second attempt started from unchanged state. A classification that treated this like a poison
    // record would have turned a five-second outage into a permanently stale product.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(priceOf(sku)).isEqualTo(1234));
    assertThat(FailOnceForSku.attempts.get()).isGreaterThanOrEqualTo(2);
    assertThat(awaitDeadLetter(sku)).isEmpty();
  }

  private static String sku() {
    return "SKU-" + UUID.randomUUID();
  }

  private ProducerRecord<String, String> productChanged(
      String sku, long revision, String name, String amount, String currency) {
    return productChanged(sku, revision, name, amount, currency, "2026-08-03T10:15:30+02:00");
  }

  private ProducerRecord<String, String> productChanged(
      String sku, long revision, String name, String amount, String currency, String changedAt) {
    String json =
        ("{\"event_kind\":\"PRODUCT_CHANGED\",\"sku_id\":\"%s\",\"display_name\":\"%s\","
                + "\"rev\":%d,\"price\":{\"amount\":\"%s\",\"currency\":\"%s\"},\"changed_at\":\"%s\"}")
            .formatted(sku, name, revision, amount, currency, changedAt);
    return record(sku, json);
  }

  private ProducerRecord<String, String> priceReduced(String sku, int percent, String messageId) {
    return record(sku, priceReducedJson(sku, percent, messageId));
  }

  private String priceReducedJson(String sku, int percent, String messageId) {
    String id = messageId == null ? "" : ",\"msg_id\":\"" + messageId + "\"";
    return ("{\"event_kind\":\"PRICE_REDUCED\",\"sku_id\":\"%s\",\"reduction_percent\":%d%s}")
        .formatted(sku, percent, id);
  }

  /**
   * Keyed by sku, which is the upstream's job and worth naming: keying by product is what gives one
   * product's changes a single partition and therefore an arrival order at all. The revision guard is
   * what makes correctness independent of whether the ERP actually does it.
   */
  private ProducerRecord<String, String> record(String sku, String json) {
    return new ProducerRecord<>(topic, sku, json);
  }

  private void send(ProducerRecord<String, String> record) {
    try {
      erp.send(record).get();
    } catch (Exception e) {
      throw new IllegalStateException("could not produce " + record, e);
    }
  }

  private List<ConsumerRecord<String, String>> awaitDeadLetter(String sku) {
    String dlt = topic + ".DLT";
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
      consumer.subscribe(List.of(dlt));
      for (int attempt = 0; attempt < 40 && collected.isEmpty(); attempt++) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
        polled
            .records(dlt)
            .forEach(
                record -> {
                  if (sku.equals(record.key())) {
                    collected.add(record);
                  }
                });
      }
    }
    return collected;
  }

  private boolean exists(String sku) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s05_product WHERE sku = ?", Long.class, sku) > 0;
  }

  /**
   * Absent reads as {@code null}, not as an exception — and that is not a style preference.
   *
   * <p>{@code untilAsserted} retries an {@link AssertionError} and lets anything else through, so a
   * helper built on {@code queryForObject} fails the whole await on its first poll with
   * {@code EmptyResultDataAccessException} the moment the row has not arrived yet. Two tests here failed
   * exactly that way while the code under test was correct: a wait that cannot express "not yet" is not a
   * wait.
   */
  private Long revisionOf(String sku) {
    return single("SELECT upstream_revision FROM s05_product WHERE sku = ?", Long.class, sku);
  }

  private Long priceOf(String sku) {
    return single("SELECT price_cents FROM s05_product WHERE sku = ?", Long.class, sku);
  }

  private String nameOf(String sku) {
    return single("SELECT name FROM s05_product WHERE sku = ?", String.class, sku);
  }

  private <T> T single(String sql, Class<T> type, Object... args) {
    List<T> rows = jdbc.queryForList(sql, type, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private long inboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class);
  }
}
