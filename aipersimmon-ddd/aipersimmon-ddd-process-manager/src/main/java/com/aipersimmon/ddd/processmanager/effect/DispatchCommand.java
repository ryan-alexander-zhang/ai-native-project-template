package com.aipersimmon.ddd.processmanager.effect;

import com.aipersimmon.ddd.cqrs.Command;

/**
 * Effect asking the runtime to send {@code command} to its handler. Delivered in-process through
 * the {@code CommandBus}, so it targets a bounded context that runs in the same process as the
 * coordinator. To reach a bounded context deployed as a separate service, use {@link
 * PublishIntegrationEvent} instead.
 *
 * @param command the command to dispatch; non-null, and carrying business fields only
 */
public record DispatchCommand(Command<?> command) implements ProcessEffect {

  public DispatchCommand {
    if (command == null) {
      throw new IllegalArgumentException("command required");
    }
  }

  @Override
  public ProcessEffectKind kind() {
    return ProcessEffectKind.DISPATCH_COMMAND;
  }
}
