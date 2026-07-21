package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;

/**
 * Delivers a {@code DispatchCommand} effect in-process via {@link CommandBus#sendAs(Command,
 * CommandContext)} — under the effect's persisted identity, verbatim, so a redelivered effect
 * reaches the handler under the same messageId and can be deduped. Only for a target bounded
 * context in the same process; a cross-service target uses an integration-event effect instead.
 */
public final class CommandEffectDispatcher implements ProcessEffectDispatcher {

  private final CommandBus commandBus;

  public CommandEffectDispatcher(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @Override
  public ProcessEffectKind kind() {
    return ProcessEffectKind.DISPATCH_COMMAND;
  }

  @Override
  public void dispatch(DecodedProcessEffect effect, CommandContext context) {
    commandBus.sendAs((Command<?>) effect.payload(), context);
  }
}
