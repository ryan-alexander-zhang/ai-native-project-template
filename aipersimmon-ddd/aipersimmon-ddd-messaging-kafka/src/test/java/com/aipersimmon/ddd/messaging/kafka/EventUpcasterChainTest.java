package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.EventUpcaster;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EventUpcasterChain}: registrations are read entirely from the two type parameters'
 * {@code @EventType} contracts and verified at construction — a mis-declared upcaster fails the
 * deployment by name, never the first old-revision record — and application walks a payload hop by
 * hop to the newest revision an upcaster leads to (issue-00142).
 */
class EventUpcasterChainTest {

  @EventType(name = "com.example.Thing", version = 1)
  record ThingV1(String id) implements IntegrationEvent {}

  @EventType(name = "com.example.Thing", version = 2)
  record ThingV2(String id, String extra) implements IntegrationEvent {}

  @EventType(name = "com.example.Thing", version = 3)
  record Thing(String id, String extra, int more) implements IntegrationEvent {}

  @EventType(name = "com.example.Other", version = 1)
  record Other(String id) implements IntegrationEvent {}

  static final class V1ToV2 implements EventUpcaster<ThingV1, ThingV2> {
    @Override
    public ThingV2 upcast(ThingV1 event) {
      return new ThingV2(event.id(), null);
    }
  }

  static final class V2ToV3 implements EventUpcaster<ThingV2, Thing> {
    @Override
    public Thing upcast(ThingV2 event) {
      return new Thing(event.id(), event.extra(), 0);
    }
  }

  @Test
  void aPayloadWalksTheWholeChainToTheNewestRevision() {
    EventUpcasterChain chain = EventUpcasterChain.of(List.of(new V2ToV3(), new V1ToV2()));

    IntegrationEvent latest = chain.upcast(new ThingV1("t-1"));

    Thing thing = assertInstanceOf(Thing.class, latest, "v1 must ride v1->v2->v3, both hops");
    assertEquals("t-1", thing.id());
  }

  @Test
  void aPayloadWithNoUpcasterIsAlreadyItsNewestRevision() {
    EventUpcasterChain chain = EventUpcasterChain.of(List.of(new V1ToV2()));
    Other other = new Other("o-1");

    assertSame(other, chain.upcast(other));
  }

  @Test
  void theTerminalVersionFollowsTheChain() {
    EventUpcasterChain chain = EventUpcasterChain.of(List.of(new V1ToV2(), new V2ToV3()));

    assertEquals(3, chain.terminalVersionOf(ThingV1.class));
    assertEquals(3, chain.terminalVersionOf(ThingV2.class));
    assertEquals(3, chain.terminalVersionOf(Thing.class));
    assertEquals(1, chain.terminalVersionOf(Other.class));
  }

  @Test
  void anUpcasterAcrossLogicalEventsIsRefusedByName() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                EventUpcasterChain.of(
                    List.of(
                        new EventUpcaster<Other, ThingV2>() {
                          @Override
                          public ThingV2 upcast(Other event) {
                            return new ThingV2(event.id(), null);
                          }
                        })));

    assertTrue(refused.getMessage().contains("maps across logical events"));
  }

  @Test
  void anUpcasterThatDoesNotIncreaseTheVersionIsRefused() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                EventUpcasterChain.of(
                    List.of(
                        new EventUpcaster<ThingV2, ThingV2>() {
                          @Override
                          public ThingV2 upcast(ThingV2 event) {
                            return event;
                          }
                        })));

    assertTrue(refused.getMessage().contains("does not increase the version"));
  }

  @Test
  void twoUpcastersForOneSourceRevisionAreRefusedByName() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> EventUpcasterChain.of(List.of(new V1ToV2(), new V1ToV2())));

    assertTrue(refused.getMessage().contains("Two upcasters registered for"));
  }

  @Test
  void anErasedTypeParameterIsRefusedAtConstruction() {
    // A method type variable resolves only to its bound (the IntegrationEvent interface) — an
    // upcaster indexed under the interface would silently never apply, so it is refused instead.
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> EventUpcasterChain.of(List.of(erased())));

    assertTrue(refused.getMessage().contains("Cannot resolve type parameter"));
  }

  private static <F extends IntegrationEvent, T extends IntegrationEvent>
      EventUpcaster<F, T> erased() {
    return new EventUpcaster<F, T>() {
      @Override
      @SuppressWarnings("unchecked")
      public T upcast(F event) {
        return (T) event;
      }
    };
  }
}
