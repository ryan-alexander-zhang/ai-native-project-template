package com.example.samples.s26.catalog.application;

import java.time.Duration;
import java.util.Optional;

/**
 * The cache, as narrow as it can be: get by key, put with an expiry, drop by key.
 *
 * <p><strong>The narrowness is the point of the whole scenario.</strong> There is no {@code list}, no
 * {@code findWhere}, no ordering, no paging — not because they were left out, but because a cache
 * cannot offer them: it holds values under keys somebody already knew how to construct. Compare
 * {@link SalesBoard}, which is a table and therefore can be sorted and filtered and paged over. That
 * asymmetry, not hit ratios, is what decides between a cache and a projection, and it is visible here
 * as the difference between two interfaces.
 *
 * <p>No port in {@code aipersimmon-ddd} corresponds to this one. The library has nothing to say about
 * caching, which leaves the shape of it to the application — so this is the sample's own interface, and
 * the only claim being made for it is that it is the smallest thing the policy above it needs.
 */
public interface QueryCache {

  /** The stored entry, if one is present and unexpired. */
  Optional<String> get(String key);

  /** Store {@code value} under {@code key}, to be forgotten after {@code ttl}. */
  void put(String key, String value, Duration ttl);

  /** Drop the entry. Absent is success: eviction is idempotent by nature. */
  void evict(String key);

  /**
   * Drop every entry whose key matches {@code pattern}, and say how many went.
   *
   * <p>The one bulk operation, here because an operator needs it: after a bad deploy, or a bug that wrote
   * wrong values for an hour, "drop this tenant's entries" is the remedy, and doing it key by key requires
   * knowing the keys. {@code CacheKeys.allOf} builds the pattern, which is the second reason the tenant
   * leads the key.
   *
   * <p><strong>The implementation must scan, not enumerate.</strong> Redis's {@code KEYS} answers this
   * question in one command and blocks the single-threaded server for the length of the whole keyspace,
   * which on a shared instance takes down every other user of it; {@code SCAN} answers it in cursored
   * batches that interleave with real traffic. The two are one method call apart in the client API and
   * several minutes of downtime apart in production.
   */
  int evictMatching(String pattern);

  /**
   * How long the entry has left, if it exists.
   *
   * <p>Here for operability rather than for the cache's own use: "when does this go stale on its own"
   * is the question an operator asks about a value that looks wrong, and jitter makes the answer
   * something you have to read rather than compute.
   */
  Optional<Duration> timeToLive(String key);
}
