package com.aipersimmon.ddd.cqrs;

/**
 * Handles one command type. A handler is thin: it loads the aggregates the
 * command touches, drives their behaviour, and persists them — cross-cutting
 * concerns such as logging, validation, and the transaction boundary are applied
 * around it by the {@link CommandBus} through {@link CommandInterceptor}s, not
 * inside the handler.
 *
 * @param <C> the command type this handler accepts
 * @param <R> the result type the command produces
 */
public interface CommandHandler<C extends Command<R>, R> {

    R handle(C command);
}
