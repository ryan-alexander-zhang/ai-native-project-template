package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
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
 * S15, consuming side: what the downstream service joins, and what it merely carries.
 *
 * <p>The test invents both — a trace id in the {@code traceparent} header and a correlation id in
 * {@code ce_correlationid} — so each assertion is that the consumer <em>adopted what arrived</em>
 * rather than minting something of its own. That distinction is the whole of distributed tracing: an
 * id that is re-minted at a boundary is not a correlation id, it is a local id with a familiar name.
 *
 * <p>Annotation set copied verbatim from {@code InboxConsumptionTest} so the classes share one context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  Probes.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class InboundTraceTest {

  private static final String KEYBOARD = "sku-keyboard";
  private static final String ACME = "acme";

  @Autowired private JdbcTemplate jdbc;
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
  void theWorkThisServiceDoesJoinsTheTraceTheRecordArrivedOn() {
    String orderId = "order-" + UUID.randomUUID();
    String upstreamTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
    ProducerRecord<String, String> record =
        WireRecords.order(
            topic,
            orderId,
            KEYBOARD,
            2,
            ACME,
            UUID.randomUUID().toString(),
            WireRecords.traceparent(upstreamTrace, "00f067aa0ba902b7"),
            "/ordering");

    WireRecords.send(producer, record);

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(recorder.all()).hasSize(1));
    Probes.Handled handled = recorder.only();
    // The command this service ran is in the PUBLISHER'S dispatch trace, extracted from the record's
    // traceparent by the consumer instrumentation. Nothing in the sample's own code reads that header:
    // it is the transport's job, and the reason the trace crosses the broker for free while it needs an
    // explicit SPI to cross the outbox.
    assertThat(handled.activeTraceId())
        .as("the consumer's span must continue the trace the record carried")
        .isEqualTo(upstreamTrace);
  }

  @Test
  void thecorrelationIdCrossesTheBrokerUnchangedAndTheCausationChainAdvances() {
    String orderId = "order-" + UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    ProducerRecord<String, String> record =
        WireRecords.order(topic, orderId, KEYBOARD, 1, ACME, eventId, null, "/ordering");
    String correlationId = new String(record.headers().lastHeader("ce_correlationid").value());

    WireRecords.send(producer, record);

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(recorder.all()).hasSize(1));
    Probes.Handled handled = recorder.only();
    // The identifier that is byte-identical from the original HTTP request to this command, with no
    // backend, no sampling and no instrumentation. InboundEvents.commandContext(envelope) is the single
    // place the conversion is written, which is why the inbound adapter is three lines long.
    assertThat(handled.context().correlationId()).isEqualTo(correlationId);
    // Causation advances rather than being carried: this command's cause is the EVENT, named by its id.
    // Following causation walks the chain hop by hop; following correlation gets the whole flow at once.
    assertThat(handled.context().causationId()).isEqualTo(eventId);
    assertThat(handled.context().messageId())
        .as("this command has its own identity")
        .isNotEqualTo(eventId);
    assertThat(handled.mdcCorrelationId()).isEqualTo(correlationId);
  }

  @Test
  void aconsumersLogLineCarriesOneOfTheFourIdsAndNotThreeOfThem() {
    String orderId = "order-" + UUID.randomUUID();
    String upstreamTrace = "0af7651916cd43dd8448eb211c80319c";

    WireRecords.send(
        producer,
        WireRecords.order(
            topic,
            orderId,
            KEYBOARD,
            1,
            ACME,
            UUID.randomUUID().toString(),
            WireRecords.traceparent(upstreamTrace, "b7ad6b7169203331"),
            "/ordering"));

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(recorder.all()).hasSize(1));
    Probes.Handled handled = recorder.only();

    // Three of the library's four MDC keys are written by SERVLET FILTERS, and a consumer has no
    // request for them to run on. So on this side of the broker a log line carries correlationId and
    // nothing else — while the tenant IS bound and the trace IS joined. This is the gap to know about
    // before an operator greps a consumer's logs for a tenant or a trace id and concludes the
    // propagation is broken.
    assertThat(handled.mdcCorrelationId())
        .as("correlationId comes from the command interceptor, so it is present on both sides")
        .isNotBlank();
    assertThat(handled.mdcTenant())
        .as("`tenant` is written by the tenancy edge filter — no request here, no key")
        .isNull();
    assertThat(handled.mdcTraceId())
        .as("`trace_id` is written by the observability edge filter — same reason")
        .isNull();

    // And the two things that ARE true, which is what makes the absences a logging gap rather than a
    // propagation failure: the work ran under the record's tenant, inside the record's trace.
    assertThat(handled.ambientTenant()).isEqualTo(ACME);
    assertThat(handled.activeTraceId()).isEqualTo(upstreamTrace);
    // Closing it costs one MDC.put in the inbound adapter, or an OTLP log appender that stamps
    // trace_id/span_id from the active context (the observability starter installs one). Which of the
    // two you want depends on whether the logs are read in a file or in a backend.
  }
}
