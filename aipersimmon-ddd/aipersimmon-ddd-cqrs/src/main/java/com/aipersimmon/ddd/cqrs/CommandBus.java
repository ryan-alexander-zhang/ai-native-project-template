package com.aipersimmon.ddd.cqrs;

/**
 * Port that dispatches a command to its single registered {@link CommandHandler},
 * applying the configured {@link CommandInterceptor} chain around the handler. It
 * is the one entry point for the write side, so logging, validation, and the
 * transaction boundary are governed in one place rather than repeated in every
 * use case.
 *
 * <p>Every dispatch runs under a {@link CommandContext} carrying the command's
 * message id and causal chain. The bus mints the command's own id; a root call
 * (no cause) seeds a fresh correlation, while {@link #send(Command, CommandContext)}
 * links the new command to a triggering message — an inbound integration event, or
 * the parent command a handler is currently running — so correlation and causation
 * propagate without ever entering the command payload.
 */
public interface CommandBus {

    /**
     * Dispatch a command with no upstream cause (a root call such as an HTTP request
     * or scheduled job). The bus establishes a fresh {@link CommandContext}.
     *
     * @param command the command to handle
     * @param <R>     the command's result type
     * @return the result produced by the handler
     */
    <R> R send(Command<R> command);

    /**
     * Dispatch a command caused by an existing message. The bus mints a fresh id for
     * this command and derives its context as a child of {@code cause}
     * ({@link CommandContext#deriveChild}), so the new command inherits the
     * correlation and trace and records {@code cause} as its causation.
     *
     * @param command the command to handle
     * @param cause   the context of the triggering message (a parent command, or an
     *                inbound integration event mapped to a {@code CommandContext})
     * @param <R>     the command's result type
     * @return the result produced by the handler
     */
    <R> R send(Command<R> command, CommandContext cause);
}
