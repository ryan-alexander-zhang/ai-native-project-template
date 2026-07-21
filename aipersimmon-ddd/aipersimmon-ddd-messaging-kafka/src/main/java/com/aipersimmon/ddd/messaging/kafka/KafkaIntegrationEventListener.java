package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.application.Inbox;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.MalformedIntegrationEventException;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes CloudEvents-encoded integration events from Kafka and hands them to local handlers. A
 * record must carry well-formed required CloudEvents attributes: {@code ce_id} (the inbox key),
 * {@code ce_type} (the catalog key), {@code ce_source}, {@code ce_specversion} (must be {@code
 * 1.0}), and {@code ce_dataschemaversion} (an integer {@code >= 1}, the other half of the catalog
 * key); {@code ce_time} is optional (it falls back to the broker record timestamp, then the local
 * clock). A record missing a required attribute, or carrying an unparseable one, is rejected as a
 * {@link MalformedIntegrationEventException} (a permanent failure, dead-lettered) rather than
 * defaulted around or retried — fabricating identity or a schema version would defeat the inbox and
 * the {@code (type, version)} contract. For a well-formed record it guards against redelivery with
 * the {@link Inbox} (keyed by {@code ce_id}); otherwise it resolves the {@code ce_type} (type,
 * version) pair to a local class via the {@link IntegrationEventCatalog} — never loading the
 * producer's class by name, dead-lettering an unknown pair — reconstructs the {@link EventEnvelope}
 * from the {@code ce_} headers and JSON payload, and republishes the envelope through Spring's
 * {@link ApplicationEventPublisher}, so beans with {@code @EventListener} handlers for {@code
 * EventEnvelope<TheEvent>} receive it with the full metadata intact — the inbound adapter is the
 * anti-corruption layer and needs that metadata.
 *
 * <p>The inbox check and the handling run in one transaction, so if a handler fails the inbox
 * record rolls back and Kafka can redeliver the record; because delivery is at-least-once, the
 * inbox is what makes reprocessing safe. If no {@code Inbox} is configured, every record is
 * republished (no deduplication), which is only safe when the handlers are themselves idempotent.
 */
public class KafkaIntegrationEventListener {

  private final ApplicationEventPublisher publisher;
  private final ObjectMapper objectMapper;
  private final Inbox inbox;
  private final IntegrationEventCatalog catalog;
  private final LocallyHandledEventTypes localHandlers;
  private final Clock clock;

  /**
   * Test/convenience: handles every type (never short-circuits).
   *
   * @param inbox the idempotency guard, or {@code null} to republish every record without
   *     deduplication
   */
  public KafkaIntegrationEventListener(
      ApplicationEventPublisher publisher,
      ObjectMapper objectMapper,
      Inbox inbox,
      IntegrationEventCatalog catalog) {
    this(
        publisher,
        objectMapper,
        inbox,
        catalog,
        LocallyHandledEventTypes.handlingEverything(),
        Clock.systemUTC());
  }

  /**
   * @param localHandlers the set of {@code (type, version)}s a local {@code @EventListener}
   *     handles; records of any other type are skipped before the inbox
   */
  public KafkaIntegrationEventListener(
      ApplicationEventPublisher publisher,
      ObjectMapper objectMapper,
      Inbox inbox,
      IntegrationEventCatalog catalog,
      LocallyHandledEventTypes localHandlers) {
    this(publisher, objectMapper, inbox, catalog, localHandlers, Clock.systemUTC());
  }

  public KafkaIntegrationEventListener(
      ApplicationEventPublisher publisher,
      ObjectMapper objectMapper,
      Inbox inbox,
      IntegrationEventCatalog catalog,
      LocallyHandledEventTypes localHandlers,
      Clock clock) {
    this.publisher = publisher;
    this.objectMapper = objectMapper;
    this.inbox = inbox;
    this.catalog = catalog;
    this.localHandlers = localHandlers;
    this.clock = clock;
  }

  @KafkaListener(
      topics = "#{@externalizedRoutes.topics()}",
      groupId =
          "${aipersimmon.ddd.messaging.kafka.consumer.group-id:${spring.application.name:aipersimmon}}")
  @Transactional
  public void onMessage(ConsumerRecord<String, String> record) {
    // Skip a record no local @EventListener handles: with no handler there is no side effect
    // to make atomic, so writing the inbox / reconstructing / republishing would be pure waste.
    // Only skip when the (type, version) are readable and definitively unhandled; anything
    // unclear falls through to the normal path below (which validates and dead-letters).
    if (skippableAsUnhandled(record)) {
      return;
    }
    // ce_id is a required CloudEvents attribute and the inbox key. A record without
    // it cannot be deduplicated; fabricating an id would make every redelivery look
    // like a new event and silently defeat the inbox — so reject it (permanent
    // failure -> dead-letter) rather than invent identity.
    String eventId = require(record, IntegrationEventHeaders.ID);
    if (inbox != null && inbox.alreadyProcessed(eventId)) {
      return;
    }
    EventEnvelope<IntegrationEvent> envelope = reconstruct(record, eventId);
    // Carry the payload's concrete type so listeners typed EventEnvelope<TheEvent>
    // match despite erasure.
    ResolvableType type =
        ResolvableType.forClassWithGenerics(EventEnvelope.class, envelope.payload().getClass());
    publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, type));
  }

  /**
   * Whether this record can be skipped because no local handler wants its type. Reads the type and
   * version leniently (no throwing): if either header is absent or unparseable we do <em>not</em>
   * skip, letting {@link #reconstruct} raise the proper malformed-record failure.
   */
  private boolean skippableAsUnhandled(ConsumerRecord<String, String> record) {
    if (localHandlers.handlesAll()) {
      return false;
    }
    String type = header(record, IntegrationEventHeaders.TYPE);
    String versionRaw = header(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION);
    if (type == null || versionRaw == null) {
      return false;
    }
    int version;
    try {
      version = Integer.parseInt(versionRaw.trim());
    } catch (NumberFormatException e) {
      return false;
    }
    // Only skip a KNOWN type that no local handler wants. An unknown (type, version) is poison
    // and must still be dead-lettered (strict inbound validation, decision-00014), so leave it
    // to the normal path rather than silently dropping it here.
    if (catalog.lookup(type, version).isEmpty()) {
      return false;
    }
    return !localHandlers.handles(type, version);
  }

  private EventEnvelope<IntegrationEvent> reconstruct(
      ConsumerRecord<String, String> record, String eventId) {
    // Validate the required CloudEvents attributes up front; a malformed one is a
    // permanent failure (dead-lettered), not a fabricated default or a retried parse.
    String type = require(record, IntegrationEventHeaders.TYPE);
    requireSpecVersion(record);
    int version = requireVersion(record);
    String source = require(record, IntegrationEventHeaders.SOURCE);
    Instant occurredAt = occurredAt(record);
    Class<? extends IntegrationEvent> eventType =
        catalog
            .lookup(type, version)
            .orElseThrow(() -> new UnknownIntegrationEventException(type, version));
    try {
      IntegrationEvent payload = objectMapper.readValue(record.value(), eventType);
      String subject = header(record, IntegrationEventHeaders.SUBJECT);
      String correlationId =
          orElse(header(record, IntegrationEventHeaders.CORRELATION_ID), eventId);
      String causationId = header(record, IntegrationEventHeaders.CAUSATION_ID);
      return new EventEnvelope<>(
          eventId, source, type, version, occurredAt, subject, correlationId, causationId, payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to reconstruct integration event of type " + type, e);
    }
  }

  /** ce_specversion is required and must be the version this binding speaks. */
  private void requireSpecVersion(ConsumerRecord<String, String> record) {
    String specVersion = require(record, IntegrationEventHeaders.SPEC_VERSION);
    if (!IntegrationEventHeaders.SPEC_VERSION_VALUE.equals(specVersion)) {
      throw new MalformedIntegrationEventException(
          "unsupported ce_specversion '"
              + specVersion
              + "'; this consumer speaks CloudEvents "
              + IntegrationEventHeaders.SPEC_VERSION_VALUE);
    }
  }

  /**
   * ce_dataschemaversion is required, an integer, and {@code >= 1} — it is half of the {@code
   * (type, version)} catalog key, so a missing or bad value cannot be defaulted.
   */
  private int requireVersion(ConsumerRecord<String, String> record) {
    String value = require(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION);
    int version;
    try {
      version = Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new MalformedIntegrationEventException(
          "ce_dataschemaversion is not an integer: '" + value + "'");
    }
    if (version < 1) {
      throw new MalformedIntegrationEventException(
          "ce_dataschemaversion must be >= 1, got " + version);
    }
    return version;
  }

  /**
   * ce_time is optional; when absent, fall back to the broker record timestamp (a receive-time
   * approximation), then to the local clock. A present but unparseable value is malformed.
   */
  private Instant occurredAt(ConsumerRecord<String, String> record) {
    String value = header(record, IntegrationEventHeaders.TIME);
    if (value == null || value.isBlank()) {
      return record.timestamp() >= 0 ? Instant.ofEpochMilli(record.timestamp()) : clock.instant();
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new MalformedIntegrationEventException(
          "ce_time is not an ISO-8601 instant: '" + value + "'");
    }
  }

  private static String orElse(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Reads a required CloudEvents header, rejecting the record if it is absent or blank. */
  private static String require(ConsumerRecord<String, String> record, String name) {
    String value = header(record, name);
    if (value == null || value.isBlank()) {
      throw new MalformedIntegrationEventException(
          "inbound Kafka record is missing required CloudEvents attribute " + name);
    }
    return value;
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
