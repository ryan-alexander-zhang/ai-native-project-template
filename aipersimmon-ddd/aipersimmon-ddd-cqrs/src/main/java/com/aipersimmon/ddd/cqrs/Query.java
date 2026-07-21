package com.aipersimmon.ddd.cqrs;

/**
 * Marker for a query: a request for data that does not change state. A query is answered from a
 * read model — a projection or view built for reading — and never goes through the aggregates or
 * the write repositories.
 *
 * <p>The type parameter is the result the query returns, so a {@link QueryBus} can dispatch it
 * type-safely.
 *
 * @param <R> the result type produced by answering this query
 */
public interface Query<R> {}
