package com.example.inventory.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * topic during a rollout, which is the worst possible moment to discover the upcaster was deleted
 * or drifted. This test is the only thing standing between "we support both revisions" and "we
 * believe we support both revisions".
 *
 * <p>The v1 path here is the one the consumer bridge takes (issue-00142): the {@link
 * OrderReadyForFulfilmentV1Upcaster} carries the retired payload to the current revision, and the
 * single listener method receives the result — there is no per-revision listener method to test
 * anymore, which is the point. No Spring context: the listener is constructed directly over the
 * framework's {@link RecordingCommandBus} (issue-00140), because what is under test is the
 * translation, not the wiring.
 */
class OrderReadyForFulfilmentVersionsTest {

  private final RecordingCommandBus bus = new RecordingCommandBus();
  private final OrderReadyForFulfilmentListener listener = new OrderReadyForFulfilmentListener(bus);
  private final OrderReadyForFulfilmentV1Upcaster upcaster =
      new OrderReadyForFulfilmentV1Upcaster();

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

    listener.on(envelope(upcast(v1(ORDER, "SKU-1", 2, "SKU-2", 1)), 2));

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
  void theUpcastCarriesEveryLineAndInventsNoDeadline() {
    OrderReadyForFulfilment upcast = upcast(v1(ORDER, "SKU-1", 3));

    assertEquals(ORDER, upcast.orderId());
    assertEquals(List.of(new OrderReadyForFulfilment.Line("SKU-1", 3)), upcast.lines());
    // The value would be ordering's configured timeout added to the publish moment, and the old
    // message carries neither. Stating no deadline is what v1 always meant; a plausible-looking
    // fabrication would be indistinguishable from a real one on the day a consumer acts on it.
    assertNull(upcast.reservationDeadline(), "what v1 never carried, the upcast must not invent");
  }

  @Test
  void theReservationCarriesEveryLineFromTheRetiredRevision() {
    listener.on(envelope(upcast(v1(ORDER, "SKU-1", 3)), 2));

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
    listener.on(envelope(upcast(v1(ORDER, "SKU-1", 1)), 2));

    // Why the ACL receives the envelope and not just the payload: the reservation stays attached to
    // the chain that caused it, and a retired revision must not quietly lose that. Upcasting
    // rewrites the payload, never the envelope's identity or causal metadata.
    assertEquals(2, bus.contexts().size());
    for (CommandContext context : bus.contexts()) {
      assertNotNull(context, "an inbound translation must carry the causal context across");
    }
  }

  private OrderReadyForFulfilment upcast(OrderReadyForFulfilmentV1 v1) {
    return upcaster.upcast(v1);
  }

  private static OrderReadyForFulfilmentV1 v1(String orderId, Object... skuQuantityPairs) {
    List<OrderReadyForFulfilmentV1.Line> lines =
        java.util.stream.IntStream.iterate(0, i -> i < skuQuantityPairs.length, i -> i + 2)
            .mapToObj(
                i ->
                    new OrderReadyForFulfilmentV1.Line(
                        (String) skuQuantityPairs[i], (Integer) skuQuantityPairs[i + 1]))
            .toList();
    return new OrderReadyForFulfilmentV1(orderId, lines);
  }

  /**
   * The envelope as the consumer bridge would deliver it after upcasting: the payload's revision (2
   * for everything reaching the listener now), the wire's identity and causal metadata untouched.
   */
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
