package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Query;

/**
 * A query that may be answered from the cache. Opt-in, per query type, by wearing this interface.
 *
 * <p>Opt-in rather than opt-out, and that is the whole design. A cache that applies to every query is
 * a cache someone has to remember to disable for the one read that must be fresh — a balance about to
 * be shown to a customer, a stock figure a decision is made on — and the failure of forgetting is
 * silent. Here the default is "not cached", so a stale answer is always something a query said yes to.
 *
 * <p>{@link #resultType()} exists because a cache stores bytes and the interceptor has to turn them
 * back into an object. It takes a {@code Class}, which means <strong>a query whose result is a generic
 * collection cannot wear this interface as it stands</strong> — {@code List<TopSeller>} is not
 * expressible as a {@code Class}, and it would need a Jackson {@code TypeReference} instead. That is a
 * real limit of this sample and not a coincidence: the one cached query here returns a single record,
 * and the list query is answered from the projection, which is where a list belongs.
 *
 * @param <R> the result type, which must be serialisable by Jackson
 */
public interface CachedQuery<R> extends Query<R> {

  /**
   * The stable part of the key: everything about this question that changes the answer, and nothing
   * else.
   *
   * <p>It must not include the tenant: {@code CacheKeys} prepends that, and it is the one segment of the
   * key allowed to be free-form, so a query that named a tenant itself would be a second free-form
   * segment and the key would stop being unambiguous. The argument is spelled out there.
   */
  String cacheKey();

  /** The type to read a stored entry back as. */
  Class<R> resultType();
}
