package com.aipersimmon.ddd.outbox.engine.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.EventDestinations;
import com.aipersimmon.ddd.outbox.engine.store.InMemoryOutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What the writer stamps onto a row, and the one thing it refuses to do.
 *
 * <p>The refusal is the reason this module exists at all: an outbox sells exactly one property —
 * the event and the state change that caused it commit together — so writing outside a transaction
 * is worse than having no outbox, and is checked rather than assumed. The rest is identity: a
 * brand-new event gets a fresh id and is caused by the command, while a replayed staged effect
 * carries the identity that was already persisted upstream, which is what makes a redelivery
 * collapse onto one row instead of becoming a second event.
 */
class OutboxWriterTest {

  @EventType(name = "com.example.ordering.OrderPlaced", version = 1)
  private record SampleEvent(String orderId) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");

  private final InMemoryOutboxStore store = new InMemoryOutboxStore();

  private OutboxWriter writerRoutedTo(EventDestinations destinations) {
    return new OutboxWriter(
        store,
        new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        "/ordering",
        destinations,
        new CapturingTracer(),
        () -> "minted-id");
  }

  /** A tracer that always has context to capture, so the row's carrier can be asserted. */
  private static final class CapturingTracer implements StoreAndForwardTracer {
    @Override
    public Captured captureCurrent() {
      return new Captured("00-trace-span-01", "vendor=1");
    }

    @Override
    public Scope restore(String traceparent, String traceState, String spanName) {
      return () -> {};
    }
  }

  @BeforeEach
  void openATransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void closeIt() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void refusesToWriteOutsideATransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                writerRoutedTo(EventDestinations.ALL_IN_PROCESS)
                    .publish(new SampleEvent("o-1"), CommandContext.root("acme", "cmd-1")));

    // Untransacted, the row commits on its own; then the caller's work rolls back and the relay
    // faithfully publishes an event announcing a change that never happened, with no error
    // anywhere and the row already gone by the time anyone looks.
    assertTrue(refused.getMessage().contains("no active transaction"));
    assertEquals(0, store.eventIds().size(), "and nothing was written");
  }

  @Test
  void aBrandNewEventGetsAFreshIdAndIsCausedByTheCommandThatPublishedIt() {
    CommandContext command = CommandContext.root("acme", "cmd-1");

    writerRoutedTo(EventDestinations.ALL_IN_PROCESS).publish(new SampleEvent("o-1"), command);

    OutboxInsert row = store.written("minted-id").orElseThrow();
    assertEquals(command.correlationId(), row.correlationId());
    assertEquals(command.messageId(), row.causationId(), "the command is the cause");
    assertEquals("o-1", row.subject(), "the aggregate the event is about, which orders its queue");
    assertEquals("/ordering", row.source());
    assertEquals(NOW, row.occurredAt());
    assertEquals("{\"orderId\":\"o-1\"}", row.payload());
  }

  @EventType(name = "com.example.inventory.StockReserved", version = 1, source = "/inventory")
  private record SourcedEvent(String orderId) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  /**
   * {@code source} names the context that produced the event, and a context is not a deployment:
   * three contexts in one process share one deployment-wide default, which made every one of their
   * events claim the same producer. An event type that declares its source on the contract wins
   * over the default — the published language owns its own provenance.
   */
  @Test
  void aSourceDeclaredOnTheContractOverridesTheDeploymentDefault() {
    writerRoutedTo(EventDestinations.ALL_IN_PROCESS)
        .publish(new SourcedEvent("o-1"), CommandContext.root("acme", "cmd-1"));

    assertEquals("/inventory", store.written("minted-id").orElseThrow().source());
  }

  @Test
  void theWritingThreadsTraceContextIsCarriedOnTheRow() {
    writerRoutedTo(EventDestinations.ALL_IN_PROCESS)
        .publish(new SampleEvent("o-1"), CommandContext.root("acme", "cmd-1"));

    OutboxInsert row = store.written("minted-id").orElseThrow();
    // The relay restores this when it dispatches, on a different thread and long after: the one
    // hop ambient context and producer auto-instrumentation cannot bridge.
    assertEquals("00-trace-span-01", row.traceparent());
    assertEquals("vendor=1", row.traceState());
  }

  @Test
  void whereTheEventIsGoingIsResolvedNowAndStoredOnTheRow() {
    writerRoutedTo((type, version) -> Optional.of("ordering.events"))
        .publish(new SampleEvent("o-1"), CommandContext.root("acme", "cmd-1"));

    assertEquals(
        "ordering.events",
        store.written("minted-id").orElseThrow().destination(),
        "resolved in the writing transaction, so the row still goes where it was addressed even "
            + "after the code that decides has changed its mind");
  }

  @Test
  void anEventWithNoExternalTargetIsMarkedInProcessRatherThanUnrouted() {
    writerRoutedTo(EventDestinations.ALL_IN_PROCESS)
        .publish(new SampleEvent("o-1"), CommandContext.root("acme", "cmd-1"));

    assertNull(store.written("minted-id").orElseThrow().destination());
  }

  @Test
  void aReplayedStagedEffectKeepsTheIdentityAlreadyPersistedUpstream() {
    // What the durable relay hands the writer: the effect's own persisted id as the message id,
    // and the transition that staged it as the cause.
    CommandContext staged = new CommandContext("acme", "effect-id-7", "corr-1", "the-cause");

    writerRoutedTo(EventDestinations.ALL_IN_PROCESS).publishAs(new SampleEvent("o-1"), staged);

    OutboxInsert row = store.written("effect-id-7").orElseThrow();
    assertEquals("the-cause", row.causationId());
    assertEquals("acme", row.tenantId());
  }

  @Test
  void aRedeliveredStagedEffectCollapsesOntoTheOneRowItAlreadyWrote() {
    // What the durable relay hands the writer: the effect's own persisted id as the message id,
    // and the transition that staged it as the cause.
    CommandContext staged = new CommandContext("acme", "effect-id-7", "corr-1", "the-cause");
    OutboxWriter writer = writerRoutedTo(EventDestinations.ALL_IN_PROCESS);
    writer.publishAs(new SampleEvent("o-1"), staged);

    writer.publishAs(new SampleEvent("o-1"), staged);

    assertEquals(
        1,
        store.eventIds().size(),
        "at-least-once upstream must not become two events downstream: the same persisted effect "
            + "id lands on the same row");
  }

  @Test
  void aDuplicateBrandNewEventIsAnErrorRatherThanSwallowed() {
    OutboxWriter writer = writerRoutedTo(EventDestinations.ALL_IN_PROCESS);
    writer.publish(new SampleEvent("o-1"), CommandContext.root("acme", "cmd-1"));

    // Only a replayed effect is idempotent. A fresh publish colliding on a minted id means the
    // id generator is broken, which is not something to absorb quietly.
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> writer.publish(new SampleEvent("o-2"), CommandContext.root("acme", "cmd-1")));
  }

  @Test
  void anUnserializableEventFailsTheWriteRatherThanStoringSomethingUnreadable() {
    @EventType(name = "com.example.ordering.Unserializable", version = 1)
    record Unserializable(Object self) implements IntegrationEvent {
      @Override
      public String subject() {
        return "o-1";
      }
    }
    Unserializable cyclic = new Unserializable(new Object());

    assertThrows(
        IllegalStateException.class,
        () ->
            writerRoutedTo(EventDestinations.ALL_IN_PROCESS)
                .publish(cyclic, CommandContext.root("acme", "cmd-1")));
  }
}
