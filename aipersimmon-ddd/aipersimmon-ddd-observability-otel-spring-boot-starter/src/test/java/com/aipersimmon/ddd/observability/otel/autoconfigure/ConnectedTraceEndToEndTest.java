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
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.outbox.jdbc.JdbcDeadLetterStore;
import com.aipersimmon.ddd.outbox.jdbc.OutboxRelay;
import com.aipersimmon.ddd.outbox.jdbc.OutboxWriter;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>Wired by hand against a real OTEL SDK + in-memory exporter so the assertion is on
 * actually-emitted spans and links; the individual capture/restore/interceptor behaviours are
 * unit-tested elsewhere, this pins their composition.
 */
class ConnectedTraceEndToEndTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private InMemorySpanExporter exporter;
  private Tracer domainTracer;
  private OutboxWriter writer;
  private OutboxRelay relay;
  private CapturingDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    DataSource dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript("classpath:aipersimmon/db/migration/outbox/h2/V1__aipersimmon_outbox.sql")
            .addScript("classpath:aipersimmon/db/migration/outbox/h2/V2__drop_trace_id.sql")
            .build();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

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

    writer = new OutboxWriter(jdbc, new ObjectMapper(), CLOCK, "test-src", storeTracer);
    dispatcher = new CapturingDispatcher();
    relay =
        new OutboxRelay(
            jdbc,
            dispatcher,
            new JdbcDeadLetterStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), CLOCK),
            new DefaultFailureClassifier(),
            new RetryBackoff(1000, 60000),
            CLOCK,
            100,
            10,
            storeTracer);
  }

  @Test
  void outboxDispatchSpanLinksBackToTheCommandSpanAcrossTheHop() {
    // A command is being handled: its span is active while the handler publishes an event.
    try (Tracer.SpanScope ignored = domainTracer.startSpan("command PlaceOrder")) {
      writer.publish(new OrderPlaced("order-1"), CommandContext.root("msg-1"));
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
