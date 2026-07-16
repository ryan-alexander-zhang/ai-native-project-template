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
 *
 * <p>Both {@code send} overloads <em>mint</em> this command's message id: for a
 * synchronous command, creation and dispatch are the same moment, so minting at
 * dispatch is correct. {@link #sendAs(Command, CommandContext)} is the exception —
 * it dispatches a command whose identity was already minted and persisted upstream
 * by a durable store (a Process Manager effect relay, an outbox), replaying it
 * verbatim. That entry is reserved for infrastructure; business code uses the two
 * {@code send} overloads.
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

    /**
     * Dispatch a command that already carries a durable message identity assigned by
     * an upstream store — a Process Manager effect relay or an outbox — re-driving it
     * at-least-once. Unlike the {@code send} overloads, the bus mints nothing and
     * derives nothing: {@code messageContext} is used verbatim, so every redelivery
     * of the same persisted message reaches the handler under the same messageId.
     * That stable identity is what lets the handler (or its inbox) dedupe.
     *
     * <p><strong>Infrastructure-only.</strong> This is not a business dispatch entry
     * point: application code and command handlers must use {@link #send(Command)} /
     * {@link #send(Command, CommandContext)}, which mint identity through the bus.
     * An ArchUnit rule guards against handler/application callers. The default
     * implementation rejects the call; a bus that backs a durable runtime overrides
     * it. See decision-00016-durable-runtime-staged-message-identity.
     *
     * @param command        the command to handle
     * @param messageContext the already-minted, persisted context to use verbatim
     * @param <R>            the command's result type
     * @return the result produced by the handler
     * @throws UnsupportedOperationException if this bus does not back a durable runtime
     */
    default <R> R sendAs(Command<R> command, CommandContext messageContext) {
        throw new UnsupportedOperationException(
                "this CommandBus does not support staged (sendAs) dispatch");
    }
}
