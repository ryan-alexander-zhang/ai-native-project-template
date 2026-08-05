package com.aipersimmon.ddd.cqrs;

/**
 * Port that dispatches a query to its single registered {@link QueryHandler}. The query side is
 * deliberately lighter than the command side: there is no transaction here, because a query neither
 * changes state nor records events. There is an optional {@link QueryInterceptor} chain — the read
 * side's seam for cross-cutting concerns such as query logging, authorization, slow-query
 * observability, or a cache that short-circuits the handler entirely. The framework registers no
 * interceptors of its own, so a bus with none behaves as a bare registry. Using the bus is
 * optional; a read port may be injected directly when routing is not needed.
 */
public interface QueryBus {

  /**
   * Dispatch a query and return its answer.
   *
   * @param query the query to answer
   * @param <R> the query's result type
   * @return the result produced by the handler
   */
  <R> R ask(Query<R> query);
}
