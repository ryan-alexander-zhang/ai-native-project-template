package com.aipersimmon.ddd.processmanager.engine.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which handler an effect goes to, and what happens when the wiring is wrong. Both
 * misconfigurations fail loudly on purpose: an effect silently going nowhere is a process that
 * waits forever on a side effect that never happened, and two handlers for one kind means one of
 * them is being ignored without anyone knowing which.
 */
class EffectDispatcherRegistryTest {

  private static final class Dispatcher implements ProcessEffectDispatcher {
    private final ProcessEffectKind kind;
    private final List<String> dispatched = new ArrayList<>();

    Dispatcher(ProcessEffectKind kind) {
      this.kind = kind;
    }

    @Override
    public ProcessEffectKind kind() {
      return kind;
    }

    @Override
    public void dispatch(DecodedProcessEffect effect, CommandContext context) {
      dispatched.add(effect.effectId());
    }
  }

  private static DecodedProcessEffect effect(String id, ProcessEffectKind kind) {
    return new DecodedProcessEffect(id, new ProcessInstanceId("instance-1"), kind, "payload");
  }

  private static final CommandContext CONTEXT =
      CommandContext.root(Tenants.of("acme"), "message-1");

  @Test
  void anEffectGoesToTheHandlerForItsKind() {
    Dispatcher commands = new Dispatcher(ProcessEffectKind.DISPATCH_COMMAND);
    Dispatcher events = new Dispatcher(ProcessEffectKind.PUBLISH_INTEGRATION_EVENT);

    new EffectDispatcherRegistry(List.of(commands, events))
        .dispatch(effect("effect-1", ProcessEffectKind.DISPATCH_COMMAND), CONTEXT);

    assertEquals(List.of("effect-1"), commands.dispatched);
    assertEquals(List.of(), events.dispatched);
  }

  @Test
  void anEffectKindWithNoHandlerFailsRatherThanBeingDroppedQuietly() {
    EffectDispatcherRegistry registry =
        new EffectDispatcherRegistry(List.of(new Dispatcher(ProcessEffectKind.DISPATCH_COMMAND)));

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                registry.dispatch(
                    effect("effect-1", ProcessEffectKind.SCHEDULE_DEADLINE), CONTEXT));

    // Failing puts the effect through the retry-then-dead path, where it is visible. Dropping it
    // would leave the process waiting on a side effect nobody is going to perform.
    assertTrue(refused.getMessage().contains("SCHEDULE_DEADLINE"));
  }

  @Test
  void twoHandlersForOneKindIsRefusedAtStartupRatherThanResolvedArbitrarily() {
    List<ProcessEffectDispatcher> ambiguous =
        List.of(
            new Dispatcher(ProcessEffectKind.DISPATCH_COMMAND),
            new Dispatcher(ProcessEffectKind.DISPATCH_COMMAND));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> new EffectDispatcherRegistry(ambiguous));

    assertTrue(refused.getMessage().contains("DISPATCH_COMMAND"));
  }
}
