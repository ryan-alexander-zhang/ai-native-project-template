/**
 * Framework-free CQRS contracts, split into a write side and a read side.
 *
 * <p>Write side: a {@link com.aipersimmon.ddd.cqrs.Command} is dispatched by the
 * {@link com.aipersimmon.ddd.cqrs.CommandBus} to its single
 * {@link com.aipersimmon.ddd.cqrs.CommandHandler}, with an ordered chain of
 * {@link com.aipersimmon.ddd.cqrs.CommandInterceptor}s (typically
 * logging → validation → transaction) applied around it. The transaction step
 * runs the handler inside a {@link com.aipersimmon.ddd.cqrs.UnitOfWork} and drains
 * the domain events of the aggregates gathered by the
 * {@link com.aipersimmon.ddd.cqrs.AggregateCollector}, so state changes and events
 * commit together.
 *
 * <p>Read side: a {@link com.aipersimmon.ddd.cqrs.Query} is answered by a
 * {@link com.aipersimmon.ddd.cqrs.QueryHandler} (optionally routed through the
 * {@link com.aipersimmon.ddd.cqrs.QueryBus}) from a
 * {@link com.aipersimmon.ddd.cqrs.ReadModel} kept up to date by a
 * {@link com.aipersimmon.ddd.cqrs.Projection}, bypassing the aggregates entirely.
 *
 * <p>Everything here is optional and depends only on the pure core tier; a Spring
 * implementation of the buses and interceptors lives in a separate starter.
 */
package com.aipersimmon.ddd.cqrs;
