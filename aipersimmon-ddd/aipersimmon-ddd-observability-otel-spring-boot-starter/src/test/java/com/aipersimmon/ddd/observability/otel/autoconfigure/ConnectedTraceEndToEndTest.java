package com.aipersimmon.ddd.observability.otel.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.observability.otel.OpenTelemetryStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.otel.OpenTelemetryTracer;
import com.aipersimmon.ddd.outbox.DefaultFailureClassifier;
import com.aipersimmon.ddd.outbox.EventDestinations;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.outbox.engine.relay.RelayLeases;
import com.aipersimmon.ddd.outbox.engine.write.OutboxWriter;
import com.aipersimmon.ddd.outbox.mybatisplus.DeadLetterMapper;
import com.aipersimmon.ddd.outbox.mybatisplus.MybatisDeadLetterStore;
import com.aipersimmon.ddd.outbox.mybatisplus.MybatisOutboxStore;
import com.aipersimmon.ddd.outbox.mybatisplus.OutboxMapper;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end proof that a trace survives the outbox store-and-forward hop as one connected trace. A
 * command span is active while the handler publishes an integration event; the OutboxWriter
 * captures that span's context onto the row; later the OutboxRelay restores it and opens an {@code
 * outbox.publish} span. The dispatch span must LINK back to the command span — that link is what
 * stitches "the request that emitted the event" to "the event actually being sent", which no
 * ambient context or producer auto-instrumentation can do across the table and the scheduler-thread
 * boundary.
 *
 * <p>Wired by hand against a real OTEL SDK + in-memory exporter (and the MyBatis-Plus outbox
 * backend over an embedded H2) so the assertion is on actually-emitted spans and links; the
 * individual capture/restore/interceptor behaviours are unit-tested elsewhere, this pins their
 * composition.
 */
class ConnectedTraceEndToEndTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private InMemorySpanExporter exporter;
  private Tracer domainTracer;
  private OutboxWriter writer;
  private OutboxRelay relay;
  private CapturingDispatcher dispatcher;
  private TransactionTemplate commandTransaction;

  @BeforeEach
  void setUp() {
    DataSource dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript("classpath:aipersimmon/db/migration/outbox/h2/V1__aipersimmon_outbox.sql")
            .addScript("classpath:aipersimmon/db/migration/outbox/h2/V2__drop_trace_id.sql")
            .addScript("classpath:aipersimmon/db/migration/outbox/h2/V3__add_tenant_id.sql")
            .addScript("classpath:aipersimmon/db/migration/outbox/h2/V4__relay_row_lease.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/outbox/h2/V5__destination_on_the_row.sql")
            .build();
    SqlSessionTemplate session = session(dataSource);
    OutboxMapper outboxMapper = session.getMapper(OutboxMapper.class);
    DeadLetterMapper deadLetterMapper = session.getMapper(DeadLetterMapper.class);
    commandTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    exporter = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .setSampler(Sampler.alwaysOn())
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    var otelTracer = sdk.getTracer("test");
    domainTracer = new OpenTelemetryTracer(otelTracer);
    var storeTracer =
        new OpenTelemetryStoreAndForwardTracer(
            otelTracer, sdk.getPropagators().getTextMapPropagator());

    MybatisOutboxStore store = new MybatisOutboxStore(outboxMapper);
    writer =
        new OutboxWriter(
            store,
            new ObjectMapper(),
            CLOCK,
            "test-src",
            EventDestinations.ALL_IN_PROCESS,
            storeTracer,
            () -> "EVT-1");
    dispatcher = new CapturingDispatcher();
    relay =
        new OutboxRelay(
            store,
            dispatcher,
            new MybatisDeadLetterStore(
                outboxMapper,
                deadLetterMapper,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                CLOCK),
            new DefaultFailureClassifier(),
            new RetryBackoff(1000, 60000),
            CLOCK,
            100,
            10,
            RelayLeases.ownedBy("otel-test", Duration.ofMinutes(5)),
            storeTracer);
  }

  @Test
  void outboxDispatchSpanLinksBackToTheCommandSpanAcrossTheHop() {
    // A command is being handled: its span is active, and its transaction is open, while the
    // handler publishes an event. Both are part of the shape being tested — the writer refuses to
    // write outside a transaction, since a row that commits alone could announce a rolled-back
    // change.
    try (Tracer.SpanScope ignored = domainTracer.startSpan("command PlaceOrder")) {
      commandTransaction.executeWithoutResult(
          status ->
              writer.publish(
                  new OrderPlaced("order-1"), CommandContext.root(Tenants.ROOT, "msg-1")));
    }

    // Later, on the scheduler thread with no ambient context, the relay dispatches the row.
    relay.relay();
    assertEquals(1, dispatcher.messages.size(), "the event must be dispatched");

    SpanData command = span("command PlaceOrder");
    SpanData publish =
        exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().startsWith("outbox.publish"))
            .findFirst()
            .orElseThrow();

    assertEquals(
        1, publish.getLinks().size(), "the dispatch span must link to the creating command span");
    assertEquals(
        command.getSpanContext().getTraceId(),
        publish.getLinks().get(0).getSpanContext().getTraceId(),
        "the link must point back at the command's trace");
    assertNotEquals(
        command.getSpanContext().getTraceId(),
        publish.getSpanContext().getTraceId(),
        "the dispatch is a new trace linked to (not a child of) the command — correct for a delayed relay");
  }

  /**
   * The outbox mappers, wired by hand over the embedded database. A {@code
   * SpringManagedTransactionFactory} is what makes the writer join the command transaction opened
   * below — a store on its own session would commit the row independently, which is the very thing
   * the outbox exists to prevent.
   */
  private static SqlSessionTemplate session(DataSource dataSource) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(
        new Environment("otel-test", new SpringManagedTransactionFactory(), dataSource));
    configuration.addMapper(OutboxMapper.class);
    configuration.addMapper(DeadLetterMapper.class);
    SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new SqlSessionTemplate(factory);
  }

  private SpanData span(String name) {
    return exporter.getFinishedSpanItems().stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  static final class CapturingDispatcher implements OutboxDispatcher {
    final List<OutboxMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(OutboxMessage message) {
      messages.add(message);
    }
  }

  @EventType(name = "com.example.ordering.ConnectedTraceOrderPlaced", version = 1)
  record OrderPlaced(String orderId) implements IntegrationEvent {}
}
