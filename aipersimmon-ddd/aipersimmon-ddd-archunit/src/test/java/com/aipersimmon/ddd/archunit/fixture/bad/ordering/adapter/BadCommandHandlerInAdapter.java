package com.aipersimmon.ddd.archunit.fixture.bad.ordering.adapter;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;

/**
 * Violates handler placement: a {@link CommandHandler} implemented in the interface/adapter layer.
 * A handler orchestrates a unit of work, which is application-layer responsibility; the adapter
 * should translate the transport into a command and delegate. Trips {@code
 * commandAndQueryHandlersShouldResideInApplication}.
 */
public class BadCommandHandlerInAdapter
    implements CommandHandler<BadCommandHandlerInAdapter.Cmd, Void> {

  @Override
  public Void handle(Cmd command, CommandContext context) {
    return null;
  }

  /** The command this misplaced handler accepts. */
  public record Cmd(String orderId) implements Command<Void> {}
}
