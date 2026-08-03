package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
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
 * S15, publishing side: what actually connects the seven hops of one business request.
 *
 * <p>Two answers, and they are not the same answer. The <strong>correlation id</strong> is identical
 * from the HTTP request to the downstream service's command — one value, byte for byte, with no
 * backend required. The <strong>trace</strong> is a graph, not a single id: it cannot be one id,
 * because the outbox deliberately breaks the synchronous chain, and a span that resumed the request's
 * trace hours later would be a lie about what happened.
 *
 * <p>Annotation set copied verbatim from {@code OutboxPublicationTest} so all three classes share one
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
class TraceAndCorrelationTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;
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
  void theDurableRowCarriesTheTraceContextOfTheRequestThatWroteIt() {
    place();

    String requestTrace = recorder.only().activeTraceId();
    assertThat(requestTrace).as("a span must be active inside the handler").isNotEqualTo("00000000000000000000000000000000");
    // The seam the SPI exists for: ambient trace context cannot survive a row written in one
    // transaction and dispatched later by a poller on another thread, and no auto-instrumentation
    // understands a self-owned table. So the writer serialises the active context onto the row, inside
    // the writing transaction, as a W3C traceparent. These two columns are the whole bridge — and they
    // stay null with no tracer installed, because capture goes through an SPI whose default captures
    // nothing.
    assertThat(storedTraceparent()).contains(requestTrace);
  }

  @Test
  void theWireLeavesUnderItsOwnTraceLinkedBackRatherThanContinuingTheRequests() {
    String orderId = idOf(place());
    String requestTrace = recorder.only().activeTraceId();

    relay.relay();

    ConsumerRecord<String, String> record = recordFor(orderId);
    String onTheWire = traceIdOf(header(record, "traceparent"));
    // The row still points at the request that wrote it...
    assertThat(storedTraceparent()).contains(requestTrace);
    // ...and the record does not. The relay restores the stored context and opens a span LINKED to the
    // creating span rather than a child of it, and the producer stamps the record with that span. The
    // library's own ConnectedTraceEndToEndTest asserts the same thing from the span side, on purpose:
    // a delayed, batched dispatch is not a continuation of the request, and pretending otherwise would
    // produce traces whose duration is "however long the row waited".
    //
    // The operational consequence is the part worth knowing: the trace id a downstream service reports
    // is NOT the trace id of the HTTP request that caused it. Following a request across the broker
    // means traversing one link — which a backend does for you, and a grep for a trace id does not.
    assertThat(onTheWire).as("the record must carry a W3C traceparent of its own").isNotNull();
    assertThat(onTheWire).isNotEqualTo(requestTrace);
  }

  @Test
  void theCorrelationIdIsTheOneIdentifierThatIsIdenticalEndToEnd() {
    String orderId = idOf(place());
    Probes.Handled handled = recorder.only();

    relay.relay();

    ConsumerRecord<String, String> record = recordFor(orderId);
    // Same value on the command, on the durable row, and on the wire — no backend, no sampling, no
    // instrumentation, and identical bytes at every hop. When the consumer turns this record into its
    // own command it keeps the value again (the consuming side's test asserts that end).
    assertThat(handled.context().correlationId()).isNotBlank();
    assertThat(storedCorrelationId()).isEqualTo(handled.context().correlationId());
    assertThat(header(record, "ce_correlationid")).isEqualTo(handled.context().correlationId());
    // And causation is the link in the chain, not the chain: the event names the command that emitted
    // it. Two ids doing two jobs — "which flow is this" and "what directly caused this".
    assertThat(header(record, "ce_causationid")).isEqualTo(handled.context().messageId());
    // A root command's correlation id is its own message id, which is what makes the flow's identity
    // available from the first hop without anything having to allocate it.
    assertThat(handled.context().correlationId()).isEqualTo(handled.context().messageId());
    assertThat(handled.context().causationId()).as("a root command has no cause").isNull();
  }

  @Test
  void theLogLineCarriesFourIdsFromFourDifferentModules() {
    place();
    Probes.Handled handled = recorder.only();

    // This is the low-cost path — what a team that never installs a collector gets. All four MDC keys
    // come from the library, each from a different module, and they are what makes seven log lines
    // greppable as one flow:
    assertThat(handled.mdcCorrelationId())
        .as("correlationId — the cqrs logging interceptor, for the duration of the handler")
        .isEqualTo(handled.context().correlationId());
    assertThat(handled.mdcTenant())
        .as("tenant — the tenancy edge filter, for the duration of the request")
        .isEqualTo("acme");
    assertThat(handled.mdcRequestId())
        .as("requestId — the web starter's edge filter; also a response header the caller can quote")
        .isNotBlank();
    assertThat(handled.mdcTraceId())
        .as("trace_id — the observability starter, present only because OpenTelemetry is installed")
        .isEqualTo(handled.activeTraceId());

    // And the gap worth knowing about, because it is the one people assume away: the caller-facing
    // request id and the messaging correlation id are DIFFERENT IDS, and nothing joins them in the
    // data. A support ticket quoting an X-Request-Id cannot be turned into a correlation-id search
    // without either the trace (the observability starter stamps request.id onto the server span for
    // exactly this) or a bridge of your own that seeds the command context from the edge id.
    assertThat(handled.mdcRequestId()).isNotEqualTo(handled.mdcCorrelationId());
  }

  private ResponseEntity<String> place() {
    return TenantRequests.post(
        http,
        "acme",
        Map.of(
            "customerId", "customer-a",
            "lines", List.of(Map.of("sku", "sku-keyboard", "quantity", 2)),
            "draftOnly", false));
  }

  private String idOf(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return JsonPath.read(response.getBody(), "$.id");
  }

  private String storedTraceparent() {
    return jdbc.queryForObject("SELECT traceparent FROM aipersimmon_outbox", String.class);
  }

  private String storedCorrelationId() {
    return jdbc.queryForObject("SELECT correlation_id FROM aipersimmon_outbox", String.class);
  }

  /** The trace id out of a W3C traceparent ({@code 00-<trace>-<span>-<flags>}), or null. */
  private static String traceIdOf(String traceparent) {
    if (traceparent == null) {
      return null;
    }
    String[] parts = traceparent.split("-");
    return parts.length >= 2 ? parts[1] : null;
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value());
  }

  private ConsumerRecord<String, String> recordFor(String orderId) {
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
    return collected.stream()
        .filter(record -> orderId.equals(record.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no record on " + topic + " keyed by " + orderId));
  }

  private KafkaConsumer<String, String> newConsumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            String.join(",", kafka.getBootstrapServers()),
            ConsumerConfig.GROUP_ID_CONFIG, "trace-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
        new StringDeserializer(),
        new StringDeserializer());
  }
}
