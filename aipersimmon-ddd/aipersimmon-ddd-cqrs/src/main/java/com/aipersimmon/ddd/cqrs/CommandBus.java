package com.aipersimmon.ddd.cqrs;

/**
 * Port that dispatches a command to its single registered {@link CommandHandler},
 * applying the configured {@link CommandInterceptor} chain around the handler. It
 * is the one entry point for the write side, so logging, validation, and the
 * transaction boundary are governed in one place rather than repeated in every
 * use case.
 */
public interface CommandBus {

    /**
     * Dispatch a command and return the handler's result.
     *
     * @param command the command to handle
     * @param <R>     the command's result type
     * @return the result produced by the handler
     */
    <R> R send(Command<R> command);
}
