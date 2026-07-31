package com.aipersimmon.ddd.cqrs;

/**
 * Marker for a query: a request for data that does not change state. By default a query is answered
 * from a read model — a projection or view built for reading — because rehydrating aggregates to
 * render lists rebuilds state and invariants a read will never use.
 *
 * <p>"By default", not "never": a single-entity read whose shape follows the aggregate's may
 * legitimately load it through the write repository and map it, trading a second read path for one
 * definition of the entity's derived values. What a query may never do is <em>mutate</em> — no
 * state change, no recorded events, whichever path answers it.
 *
 * <p>The type parameter is the result the query returns, so a {@link QueryBus} can dispatch it
 * type-safely.
 *
 * @param <R> the result type produced by answering this query
 */
public interface Query<R> {}
