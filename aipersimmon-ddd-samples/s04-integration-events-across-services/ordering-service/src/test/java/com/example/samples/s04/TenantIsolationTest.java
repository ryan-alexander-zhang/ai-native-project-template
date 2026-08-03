package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.tenancy.MissingTenantException;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.mybatisplus.TenantTableRegistrationGuard;
import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S13, publishing side: the tenant from the edge to the durable row to the wire, and the ways it can
 * legitimately be absent.
 *
 * <p>The annotation set is copied verbatim from {@code OutboxPublicationTest} so the two share one
 * context and one container pair.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"aipersimmon.ddd.outbox.relay.enabled=false"})
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  FailAfterHandling.class,
  Probes.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class TenantIsolationTest {

  private static final String ACME = "acme";
  private static final String GLOBEX = "globex";

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;
  @Autowired private DataSource dataSource;
  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private Probes.Recorder recorder;

  @Value("${ordering.events-topic}")
  private String topic;

  private KafkaConsumer<String, String> consumer;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM s04_order_line");
    jdbc.update("DELETE FROM s04_order");
    recorder.clear();
    consumer = newConsumer();
    consumer.subscribe(List.of(topic));
    awaitAssignment();
    consumer.seekToBeginning(consumer.assignment());
  }

  @AfterEach
  void tearDown() {
    consumer.close();
  }

  private void awaitAssignment() {
    for (int attempt = 0; attempt < 30 && consumer.assignment().isEmpty(); attempt++) {
      consumer.poll(Duration.ofMillis(200));
    }
    assertThat(consumer.assignment()).as("the test consumer was never assigned a partition").isNotEmpty();
  }

  @Test
  void theTenantIsStampedOnEveryRowWithoutAnyCodeMentioningIt() {
    String acmeOrder = idOf(place(ACME, "customer-a"));
    String globexOrder = idOf(place(GLOBEX, "customer-b"));

    // Neither the aggregate, the handler, the repository nor the row class mentions tenant_id. The
    // interceptor added the column and the value to both inserts, root and child, from the ambient
    // context — which is the difference between SQL-level rewriting and a hand-written predicate:
    // there is no per-statement discipline, so there is no statement that can forget.
    assertThat(tenantOf("s04_order", acmeOrder)).isEqualTo(ACME);
    assertThat(tenantOf("s04_order", globexOrder)).isEqualTo(GLOBEX);
    assertThat(
            jdbc.queryForObject(
                "SELECT tenant_id FROM s04_order_line WHERE order_id = ?", String.class, acmeOrder))
        .isEqualTo(ACME);
    // And the command carried it too: the bus seeded CommandContext from the ambient tenant, so a
    // handler that publishes an event does not have to know where the tenant came from.
    assertThat(recorder.all()).allSatisfy(handled -> assertThat(handled.ambientTenant()).isNotBlank());
  }

  @Test
  void aforeignTenantsOrderIdReadsAsNotFound() {
    String acmeOrder = idOf(place(ACME, "customer-a"));

    assertThat(TenantRequests.get(http, ACME, "/orders/" + acmeOrder).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    // The interesting half. Not 403: a 403 confirms the id exists, and a caller who guessed another
    // tenant's id is not entitled to that confirmation either. "Absent" and "not yours" must be the
    // same answer, and the way to get that for free is for the query itself to be unable to see it.
    assertThat(TenantRequests.get(http, GLOBEX, "/orders/" + acmeOrder).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void arequestThatResolvesNoTenantIsRejectedAtTheEdge() {
    ResponseEntity<String> response =
        TenantRequests.postWithoutTenant(
            http,
            Map.of(
                "customerId", "customer-a",
                "lines", List.of(Map.of("sku", "sku-keyboard", "quantity", 1)),
                "draftOnly", false));

    // The default missing-policy is REJECT, and it is the right default: a request with no tenant has
    // no safe interpretation. The alternative (SYSTEM, binding __root__) writes into the sentinel
    // bucket, which in a deployment that migrated from single-tenant holds real production data.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(count("s04_order")).isZero();
  }

  @Test
  void therelayIsNotTenantScopedAndDrainsEveryTenant() {
    String acmeOrder = idOf(place(ACME, "customer-a"));
    String globexOrder = idOf(place(GLOBEX, "customer-b"));

    // One poll, on a thread with no tenant bound at all.
    relay.relay();

    // Both tenants' events ship, each carrying its own tenant on the wire. This is why the framework's
    // aipersimmon_* tables are absent from tenant-tables and exempt from the guard by construction:
    // listing them would put a tenant predicate on the relay's scan, and with tenancy enabled an
    // unbound thread does not fall back to the sentinel — it throws. Every poll would fail.
    // Drained once and filtered twice: a topic is not a queue, but a consumer's position is consumed —
    // polling again for the second order would find nothing, because the first call read past it.
    List<ConsumerRecord<String, String>> shipped = drainTopic();
    assertThat(tenantIdOf(recordIn(shipped, acmeOrder))).isEqualTo(ACME);
    assertThat(tenantIdOf(recordIn(shipped, globexOrder))).isEqualTo(GLOBEX);
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT tenant_id) FROM aipersimmon_outbox", Long.class))
        .isEqualTo(2);
  }

  @Test
  void aTenantLessThreadFailsClosedRatherThanReadingTheSentinelBucket() {
    // The same call the relay makes safely — because it touches only stamped columns — but through a
    // path that resolves the tenant as a predicate. Nothing is bound on this thread, and with tenancy
    // enabled that is an error rather than a quiet narrowing to __root__.
    assertThatThrownBy(TenantContext::effective).isInstanceOf(MissingTenantException.class);
  }

  @Test
  void theRootSentinelIsABucketNotAWildcard() {
    place(ACME, "customer-a");
    place(GLOBEX, "customer-b");

    // Reading "as root" does not read every tenant: __root__ is an ordinary value in an ordinary
    // column, so a query scoped to it matches the rows stamped with it — none, here. There is no
    // all-tenants mode in the interceptor, and that is deliberate.
    assertThat(countWhereTenantIs("__root__")).isZero();
    // A genuine cross-tenant read is therefore a DIFFERENT query path — this one, with no predicate at
    // all — and it is the most dangerous code in a multi-tenant service: every isolation guarantee
    // above is void inside it. It has to be authorised separately (a platform role, not a tenant
    // user's role), it must not be reachable from a tenant-facing endpoint, and "we needed it for
    // support" is how it ends up behind one.
    assertThat(count("s04_order")).isEqualTo(2);
  }

  @Test
  void anUnregisteredTenantCarryingTableIsRefusedByTheStartupGuard() {
    // The allow-list fails open: an unlisted table gets no tenant predicate on any statement, so every
    // tenant reads and writes every tenant's rows and nothing errors. A column default cannot save the
    // read path. So the library checks the list's completeness against the live schema at startup —
    // here, driven directly against the same database with s04_order_line deliberately left out.
    TenantTableRegistrationGuard incomplete =
        new TenantTableRegistrationGuard(
            dataSource, "tenant_id", List.of("s04_order"), List.of());

    assertThatThrownBy(incomplete::verify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("s04_order_line")
        .hasMessageContaining("NO tenant predicate");

    // And the list this service actually ships with passes — which is what makes the assertion above
    // a statement about the guard rather than about a broken configuration.
    new TenantTableRegistrationGuard(
            dataSource, "tenant_id", List.of("s04_order", "s04_order_line"), List.of())
        .verify();
  }

  private ResponseEntity<String> place(String tenant, String customerId) {
    return TenantRequests.post(
        http,
        tenant,
        Map.of(
            "customerId", customerId,
            "lines", List.of(Map.of("sku", "sku-keyboard", "quantity", 1)),
            "draftOnly", false));
  }

  private String idOf(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return JsonPath.read(response.getBody(), "$.id");
  }

  private String tenantOf(String table, String id) {
    return jdbc.queryForObject(
        "SELECT tenant_id FROM " + table + " WHERE id = ?", String.class, id);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private long countWhereTenantIs(String tenant) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM s04_order WHERE tenant_id = ?", Long.class, tenant);
  }

  private static String tenantIdOf(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader("ce_tenantid");
    return header == null ? null : new String(header.value());
  }

  private List<ConsumerRecord<String, String>> drainTopic() {
    List<ConsumerRecord<String, String>> collected = new ArrayList<>();
    for (int emptyPolls = 0; emptyPolls < 3; ) {
      ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(1));
      if (polled.isEmpty()) {
        emptyPolls++;
        continue;
      }
      emptyPolls = 0;
      polled.records(topic).forEach(collected::add);
    }
    return collected;
  }

  private static ConsumerRecord<String, String> recordIn(
      List<ConsumerRecord<String, String>> records, String orderId) {
    return records.stream()
        .filter(record -> orderId.equals(record.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no shipped record keyed by " + orderId));
  }

  private KafkaConsumer<String, String> newConsumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            String.join(",", kafka.getBootstrapServers()),
            ConsumerConfig.GROUP_ID_CONFIG, "tenant-isolation-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
        new StringDeserializer(),
        new StringDeserializer());
  }
}
