package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandContexts;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The bus binds the dispatch context to {@link CommandContexts} for the duration of the dispatch
 * (issue-00137). The reader this exists for is a synchronous domain-event subscriber: it runs on
 * the handler's call stack — the repository's {@code save} publishes the aggregate's events before
 * the handler returns — but a Spring {@code @EventListener} method signature has no place for a
 * {@code CommandContext} parameter. Before this scope existed, such a subscriber could only mint a
 * fresh root context, so the causal chain the bus works to maintain broke at every domain-event
 * hop.
 */
class DispatchScopedCommandContextTest {

  record Place(String orderId) implements Command<String> {}

  record Reserve(String sku) implements Command<String> {}

  /** Stands in for the domain-event subscriber: reads the ambient context mid-dispatch. */
  static final class AmbientReadingPlaceHandler implements CommandHandler<Place, String> {
    final List<CommandContext> ambientSeen = new ArrayList<>();
    Runnable midHandle = () -> {};

    @Override
    public String handle(Place command, CommandContext context) {
      ambientSeen.add(CommandContexts.current().orElse(null));
      midHandle.run();
      return command.orderId();
    }
  }

  static final class AmbientReadingReserveHandler implements CommandHandler<Reserve, String> {
    final List<CommandContext> ambientSeen = new ArrayList<>();

    @Override
    public String handle(Reserve command, CommandContext context) {
      ambientSeen.add(CommandContexts.current().orElse(null));
      return command.sku();
    }
  }

  @Test
  void aSubscriberOnTheHandlersCallStackSeesTheDispatchContext() {
    AmbientReadingPlaceHandler handler = new AmbientReadingPlaceHandler();
    AtomicInteger ids = new AtomicInteger();
    CommandBus bus =
        new RegistryCommandBus(List.of(handler), List.of(), () -> "id-" + ids.incrementAndGet());

    bus.send(new Place("order-1"));

    CommandContext ambient = handler.ambientSeen.get(0);
    assertEquals("id-1", ambient.messageId());
    assertEquals("id-1", ambient.correlationId());
    assertNull(ambient.causationId());
    assertTrue(CommandContexts.current().isEmpty(), "the binding must not outlive the dispatch");
  }

  @Test
  void aNestedSendBindsTheChildAndRestoresTheParent() {
    AmbientReadingPlaceHandler outer = new AmbientReadingPlaceHandler();
    AmbientReadingReserveHandler inner = new AmbientReadingReserveHandler();
    AtomicInteger ids = new AtomicInteger();
    CommandBus bus =
        new RegistryCommandBus(
            List.of(outer, inner), List.of(), () -> "id-" + ids.incrementAndGet());
    outer.midHandle =
        () -> {
          CommandContext mine = CommandContexts.current().orElseThrow();
          bus.send(new Reserve("sku-1"), mine);
          assertEquals(
              mine,
              CommandContexts.current().orElseThrow(),
              "after the nested dispatch returns, the outer context is ambient again");
        };

    bus.send(new Place("order-1"));

    CommandContext child = inner.ambientSeen.get(0);
    assertEquals("id-2", child.messageId());
    assertEquals("id-1", child.correlationId(), "the child stays on the parent's correlation");
    assertEquals("id-1", child.causationId());
    assertTrue(CommandContexts.current().isEmpty());
  }

  @Test
  void sendAsBindsTheVerbatimContext() {
    AmbientReadingPlaceHandler handler = new AmbientReadingPlaceHandler();
    CommandBus bus =
        new RegistryCommandBus(
            List.of(handler),
            List.of(),
            () -> {
              throw new AssertionError("sendAs must not mint an id");
            });
    CommandContext effectCtx = CommandContext.root(com.aipersimmon.ddd.tenancy.Tenants.ROOT, "e-1");

    bus.sendAs(new Place("order-1"), effectCtx);

    assertEquals(effectCtx, handler.ambientSeen.get(0));
    assertTrue(CommandContexts.current().isEmpty());
  }

  @Test
  void aFailedDispatchStillUnbinds() {
    AmbientReadingPlaceHandler handler = new AmbientReadingPlaceHandler();
    handler.midHandle =
        () -> {
          throw new IllegalStateException("handler failure");
        };
    CommandBus bus = new RegistryCommandBus(List.of(handler), List.of(), () -> "id-1");

    assertThrows(IllegalStateException.class, () -> bus.send(new Place("order-1")));

    assertTrue(
        CommandContexts.current().isEmpty(),
        "a failed dispatch must not leak its context onto the thread");
  }
}
