package com.aipersimmon.ddd.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The identity rules the recording bus must keep — they are the reason it exists: a hand-rolled
 * fake that mints nothing makes causation assertions pass vacuously, and one that derives on {@code
 * sendAs} makes redelivery-dedup untestable (issue-00140).
 */
@WithTenant("acme")
class RecordingCommandBusTest {

  record Reserve(String sku) implements Command<String> {}

  record Release(String sku) implements Command<Void> {}

  private final RecordingCommandBus bus = new RecordingCommandBus();

  @Test
  void aRootSendMintsAFreshContextUnderTheAmbientTenant() {
    bus.send(new Reserve("sku-1"));
    bus.send(new Reserve("sku-2"));

    List<CommandContext> contexts = bus.contexts();
    assertEquals(Tenants.of("acme"), contexts.get(0).tenantId());
    assertEquals(contexts.get(0).messageId(), contexts.get(0).correlationId());
    assertNotEquals(
        contexts.get(0).correlationId(),
        contexts.get(1).correlationId(),
        "each root send seeds its own correlation, exactly like the real bus");
    assertEquals(RecordingCommandBus.Kind.ROOT, bus.dispatches().get(0).kind());
  }

  @Test
  void aCausedSendDerivesAChildOfItsCause() {
    CommandContext cause = CommandContext.root(Tenants.of("acme"), "parent-1");

    bus.send(new Reserve("sku-1"), cause);

    CommandContext child = bus.contexts().get(0);
    assertEquals("parent-1", child.causationId(), "the cause is named");
    assertEquals(cause.correlationId(), child.correlationId(), "the flow is kept");
    assertNotEquals("parent-1", child.messageId(), "the child gets its own minted id");
    assertEquals(RecordingCommandBus.Kind.CHILD, bus.dispatches().get(0).kind());
  }

  @Test
  void sendAsRecordsTheContextVerbatimMintingNothing() {
    CommandContext staged = new CommandContext(Tenants.of("acme"), "effect-7", "corr-1", "cause-1");

    bus.sendAs(new Reserve("sku-1"), staged);

    // The one rule that makes redelivery testable: the same persisted effect reaches the handler
    // under the same messageId, every time.
    assertEquals(staged, bus.contexts().get(0));
    assertEquals(RecordingCommandBus.Kind.STAGED, bus.dispatches().get(0).kind());
  }

  @Test
  void aStubbedCommandTypeAnswersAndOthersReturnNull() {
    bus.returning(Reserve.class, reserve -> "reserved:" + reserve.sku());

    assertEquals("reserved:sku-1", bus.send(new Reserve("sku-1")));
    assertNull(bus.send(new Release("sku-1")));
  }

  @Test
  void commandsCanBeReadBackByType() {
    bus.send(new Reserve("sku-1"));
    bus.send(new Release("sku-1"));
    bus.send(new Reserve("sku-2"));

    assertEquals(
        List.of(new Reserve("sku-1"), new Reserve("sku-2")), bus.commandsOf(Reserve.class));
    assertEquals(3, bus.commands().size());
  }
}
