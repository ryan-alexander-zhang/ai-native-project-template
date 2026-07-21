package com.aipersimmon.ddd.cqrs;

/**
 * Marker for a command: a first-class, task-based object that names an intent to change state (for
 * example {@code PlaceOrder}). A command is handled by exactly one {@link CommandHandler}, which
 * orchestrates the aggregates and holds no business rules of its own.
 *
 * <p>The type parameter is the result the handler returns — often the identity of a newly created
 * aggregate. Use {@link Void} for a command that returns nothing; its handler returns {@code null}.
 *
 * @param <R> the result type produced by handling this command
 */
public interface Command<R> {}
