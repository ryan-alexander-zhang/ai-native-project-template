package com.aipersimmon.ddd.cqrs;

/**
 * Answers one query type by reading from a read model. Like a command handler it is registered with
 * the {@link QueryBus}, but the query side is often simple enough to inject a query port directly
 * and skip the bus entirely.
 *
 * @param <Q> the query type this handler accepts
 * @param <R> the result type the query produces
 */
public interface QueryHandler<Q extends Query<R>, R> {

  R handle(Q query);
}
