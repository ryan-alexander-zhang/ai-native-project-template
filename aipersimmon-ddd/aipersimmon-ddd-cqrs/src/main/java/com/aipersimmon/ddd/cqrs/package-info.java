/**
 * Framework-free CQRS contracts, split into a write side and a read side.
 *
 * <p>Write side: a {@link com.aipersimmon.ddd.cqrs.Command} is dispatched by the {@link
 * com.aipersimmon.ddd.cqrs.CommandBus} to its single {@link
 * com.aipersimmon.ddd.cqrs.CommandHandler}, with an ordered chain of {@link
 * com.aipersimmon.ddd.cqrs.CommandInterceptor}s (typically logging → validation → transaction)
 * applied around it. Each dispatch carries a {@link com.aipersimmon.ddd.cqrs.CommandContext}
 * (message id, correlation, causation, trace) beside the command — never inside the payload — so
 * logs and any integration events emitted while handling stay correlated to what triggered them.
 * The transaction step runs the handler inside a {@link com.aipersimmon.ddd.cqrs.UnitOfWork}; the
 * domain events an aggregate records are drained where it is saved, within that same transaction,
 * so state changes and events commit together.
 *
 * <p>Read side: a {@link com.aipersimmon.ddd.cqrs.Query} is answered by a {@link
 * com.aipersimmon.ddd.cqrs.QueryHandler} (optionally routed through the {@link
 * com.aipersimmon.ddd.cqrs.QueryBus}) from a {@link com.aipersimmon.ddd.cqrs.ReadModel} kept up to
 * date by a {@link com.aipersimmon.ddd.cqrs.Projection}, bypassing the aggregates entirely.
 *
 * <p>Everything here is optional and depends only on the pure core tier; a Spring implementation of
 * the buses and interceptors lives in a separate starter.
 */
package com.aipersimmon.ddd.cqrs;
