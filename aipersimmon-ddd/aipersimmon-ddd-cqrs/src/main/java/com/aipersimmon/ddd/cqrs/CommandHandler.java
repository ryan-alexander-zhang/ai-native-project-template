package com.aipersimmon.ddd.cqrs;

/**
 * Handles one command type. A handler is thin: it loads the aggregates the command touches, drives
 * their behaviour, and persists them — cross-cutting concerns such as logging, validation, and the
 * transaction boundary are applied around it by the {@link CommandBus} through {@link
 * CommandInterceptor}s, not inside the handler.
 *
 * <p>The handler receives the command's {@link CommandContext} alongside the command. Most handlers
 * ignore it; a handler that emits an integration event passes it on (see {@code
 * IntegrationEvents.publish}) so the outbound event is correlated to this command and records it as
 * the cause.
 *
 * @param <C> the command type this handler accepts
 * @param <R> the result type the command produces
 */
public interface CommandHandler<C extends Command<R>, R> {

  R handle(C command, CommandContext context);
}
