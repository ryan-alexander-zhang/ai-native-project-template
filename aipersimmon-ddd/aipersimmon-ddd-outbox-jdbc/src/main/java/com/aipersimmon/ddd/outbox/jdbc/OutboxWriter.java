package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.application.DurableIntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Captured;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes an integration event into the outbox table in the caller's transaction. It stamps the
 * transport metadata (an event id, the event's class name as the type, the event's declared
 * version, the current time) and the causal chain from the emitting command's {@link
 * CommandContext} — correlation, causation (the command's message id), and trace — into an {@link
 * EventEnvelope}, serializes the event payload to JSON, and inserts one row. Being part of the
 * caller's transaction, the row commits atomically with the aggregate change.
 *
 * <p>{@link #publish} mints a fresh event id for a new event; {@link #publishAs} reuses the
 * persisted identity a durable relay assigns (event id equal to the effect id) and inserts
 * idempotently, so an at-least-once redelivery of the same staged effect writes the row once.
 */
public class OutboxWriter implements DurableIntegrationEvents {

  private static final String INSERT =
      "INSERT INTO aipersimmon_outbox "
          + "(event_id, source, type, version, payload, occurred_at, subject, "
          + "tenant_id, correlation_id, causation_id, traceparent, trace_state, sent, attempts, "
          + "created_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String source;
  private final StoreAndForwardTracer tracer;
  private final Supplier<String> idGenerator;

  public OutboxWriter(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      Clock clock,
      String source,
      Supplier<String> idGenerator) {
    this(jdbc, objectMapper, clock, source, NoOpStoreAndForwardTracer.INSTANCE, idGenerator);
  }

  /**
   * @param idGenerator supplies each brand-new event's id. Required: there is no defaulting
   *     overload, so a caller cannot accidentally fall back to a random UUID and lose index
   *     locality on the {@code event_id} unique index (see {@code issue-00053}). Auto-configuration
   *     passes the {@link com.aipersimmon.ddd.core.id.IdGenerator} bean; tests pass a deterministic
   *     supplier.
   */
  public OutboxWriter(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      Clock clock,
      String source,
      StoreAndForwardTracer tracer,
      Supplier<String> idGenerator) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.source = source;
    this.tracer = tracer;
    this.idGenerator = idGenerator;
  }

  @Override
  public void publish(IntegrationEvent event, CommandContext context) {
    // A brand-new event caused by the command described by context: mint a fresh event
    // id and record the command (context.messageId()) as the cause.
    write(
        event,
        idGenerator.get(),
        context.tenantId(),
        context.correlationId(),
        context.messageId(),
        false);
  }

  @Override
  public void publishAs(IntegrationEvent event, CommandContext context) {
    // A staged effect replayed by the durable relay: identity and causal chain were minted
    // and persisted upstream, so stamp them verbatim — event id = the persisted effect id
    // (context.messageId()), cause = context.causationId(). The insert is idempotent: a
    // redelivery re-inserting the same event id collapses onto the existing row, so the one
    // logical event is written once and the downstream inbox dedupes redeliveries by it.
    write(
        event,
        context.messageId(),
        context.tenantId(),
        context.correlationId(),
        context.causationId(),
        true);
  }

  private void write(
      IntegrationEvent event,
      String eventId,
      String tenantId,
      String correlationId,
      String causationId,
      boolean idempotent) {
    requireActiveTransaction(event);
    String payload = serialize(event);
    // Capture the trace context active on this (writing) thread so the relay can restore it
    // when it dispatches the row later on the scheduler thread — the one hop ambient context
    // and Kafka producer auto-instrumentation cannot bridge across the outbox table.
    Captured captured = tracer.captureCurrent();
    EventEnvelope<IntegrationEvent> envelope =
        new EventEnvelope<>(
            eventId,
            source,
            IntegrationEvent.eventTypeOf(event.getClass()),
            IntegrationEvent.eventVersionOf(event.getClass()),
            clock.instant(),
            event.subject(),
            tenantId,
            correlationId,
            causationId,
            event);
    try {
      jdbc.update(
          INSERT,
          envelope.eventId(),
          envelope.source(),
          envelope.type(),
          envelope.version(),
          payload,
          Timestamp.from(envelope.occurredAt()),
          envelope.subject(),
          envelope.tenantId(),
          envelope.correlationId(),
          envelope.causationId(),
          captured.traceparent(),
          captured.traceState(),
          false,
          0,
          Timestamp.from(clock.instant()));
    } catch (DuplicateKeyException alreadyWritten) {
      if (!idempotent) {
        throw alreadyWritten;
      }
      // Same event id already in the outbox: an earlier delivery of this staged effect
      // committed the row before the relay could mark the effect delivered. Nothing to do.
    }
  }

  /**
   * Refuse to write the row outside a transaction.
   *
   * <p>An outbox exists for exactly one property: the event and the state change that caused it
   * commit together. Untransacted, this row commits on its own — and then the caller's own work
   * fails, or is rolled back by something above it, and the relay faithfully publishes an event
   * announcing a change that never happened. Downstream cannot tell the difference, there is no
   * error anywhere, and the row is already gone from the outbox by the time anyone looks. That is
   * strictly worse than not having an outbox, which is why the one guarantee it sells is checked
   * rather than assumed.
   */
  private void requireActiveTransaction(IntegrationEvent event) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    throw new IllegalStateException(
        "no active transaction while writing "
            + IntegrationEvent.eventTypeOf(event.getClass())
            + " to the outbox: the row must commit with the state change that caused it, or the"
            + " relay will publish an event for a change that was rolled back. Publish from inside"
            + " a command handler (the CommandBus opens a transaction), or annotate the calling"
            + " application service with @Transactional.");
  }

  private String serialize(IntegrationEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "failed to serialize integration event: " + event.getClass().getName(), e);
    }
  }
}
