package com.example.inventory.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.test.RecordingCommandBus;
import com.example.inventory.application.stock.ReserveStock;
import com.example.ordering.api.OrderReadyForFulfilment;
import com.example.ordering.api.OrderReadyForFulfilmentV1;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The consumer's half of the v1/v2 coexistence example: <strong>both revisions of ordering's
 * contract must produce the same {@link ReserveStock} command.</strong>
 *
 * <p>That is the property a version bump has to preserve, and it is the one no integration test
 * would notice losing. An end-to-end test only ever sees whatever revision the producer currently
 * publishes, so the retired path is exercised by nothing — until a real v1 message arrives from the
 * topic during a rollout, which is the worst possible moment to discover the listener was deleted
 * or drifted. This test is the only thing standing between "we support both revisions" and "we
 * believe we support both revisions".
 *
 * <p>No Spring context: the listener is constructed directly over the framework's {@link
 * RecordingCommandBus} (issue-00140), because what is under test is the translation, not the
 * wiring.
 */
class OrderReadyForFulfilmentVersionsTest {

  private final RecordingCommandBus bus = new RecordingCommandBus();
  private final OrderReadyForFulfilmentListener listener = new OrderReadyForFulfilmentListener(bus);

  private static final String ORDER = "0197c1e2-0a3b-7c4d-8e5f-6a7b8c9d0e1f";

  @Test
  void bothRevisionsProduceTheSameReservationCommand() {
    listener.on(
        envelope(
            new OrderReadyForFulfilment(
                ORDER,
                List.of(
                    new OrderReadyForFulfilment.Line("SKU-1", 2),
                    new OrderReadyForFulfilment.Line("SKU-2", 1)),
                // v2's addition. Inventory does not act on it, so it must not change the command.
                Instant.parse("2026-07-28T12:01:00Z")),
            2));

    listener.onV1(
        envelope(
            new OrderReadyForFulfilmentV1(
                ORDER,
                List.of(
                    new OrderReadyForFulfilmentV1.Line("SKU-1", 2),
                    new OrderReadyForFulfilmentV1.Line("SKU-2", 1))),
            1));

    assertEquals(
        2, bus.commands().size(), "each revision should have produced exactly one command");
    assertEquals(
        bus.commands().get(0),
        bus.commands().get(1),
        "a v1 and a v2 message describing the same order must reach the application layer as the"
            + " same command — if these differ, a rollout would reserve stock differently depending"
            + " on which revision happened to arrive");
  }

  @Test
  void theReservationCarriesEveryLineFromEitherRevision() {
    listener.onV1(
        envelope(
            new OrderReadyForFulfilmentV1(
                ORDER, List.of(new OrderReadyForFulfilmentV1.Line("SKU-1", 3))),
            1));

    ReserveStock reservation = bus.commandsOf(ReserveStock.class).getFirst();
    assertEquals(ORDER, reservation.orderId());
    assertEquals(List.of(new ReserveStock.Line("SKU-1", 3)), reservation.lines());
  }

  @Test
  void theCausalContextSurvivesTheTranslationOnBothPaths() {
    listener.on(
        envelope(
            new OrderReadyForFulfilment(
                ORDER,
                List.of(new OrderReadyForFulfilment.Line("SKU-1", 1)),
                Instant.parse("2026-07-28T12:01:00Z")),
            2));
    listener.onV1(
        envelope(
            new OrderReadyForFulfilmentV1(
                ORDER, List.of(new OrderReadyForFulfilmentV1.Line("SKU-1", 1))),
            1));

    // Why the ACL receives the envelope and not just the payload: the reservation stays attached to
    // the chain that caused it, and a retired revision must not quietly lose that.
    assertEquals(2, bus.contexts().size());
    for (CommandContext context : bus.contexts()) {
      assertNotNull(context, "an inbound translation must carry the causal context across");
    }
  }

  private static <T extends IntegrationEvent> EventEnvelope<T> envelope(T payload, int version) {
    return new EventEnvelope<>(
        "evt-" + version,
        "ordering",
        "com.example.ordering.OrderReadyForFulfilment",
        version,
        Instant.parse("2026-07-28T12:00:00Z"),
        payload.subject(),
        "demo",
        "corr-1",
        "cause-1",
        payload);
  }
}
