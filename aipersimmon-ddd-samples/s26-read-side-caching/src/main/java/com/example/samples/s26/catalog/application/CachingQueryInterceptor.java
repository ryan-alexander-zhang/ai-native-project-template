package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * The cache, as a {@link QueryInterceptor}: the read side's one seam, used for the thing it was added
 * for.
 *
 * <p><strong>Why here and not in the repository.</strong> A cache in the read adapter would be invisible
 * to the layer that decides policy, would have to be re-implemented for every adapter, and — the part
 * that actually bites — could not short-circuit. Here it can: an interceptor that does not call {@link
 * Invocation#proceed()} answers without the handler ever running, so a hit costs one round trip to
 * Redis and nothing else. The library's own javadoc names this as one of the two intended uses of not
 * proceeding ("a cache, an authorization refusal"), and it is the only place in the read path where a
 * decision can be made <em>before</em> the query reaches whoever would answer it.
 *
 * <p>Ordered at {@code 100}: any logging or authorization interceptor belongs outside it (a lower
 * order), because a cache hit must still be logged and must still be refused to a caller who may not
 * see the data. Getting that backwards is an authorization check that a warm cache skips.
 *
 * <p>Three things it deliberately does not do:
 *
 * <ul>
 *   <li><strong>It does not cache {@code null}.</strong> Negative caching would stop an unknown sku
 *       from reaching the database on every request, and it would also let anyone fill the keyspace by
 *       asking for skus that do not exist. Without a bound on that, the cheaper failure is the one
 *       that stays in the database.
 *   <li><strong>It does not participate in the transaction.</strong> A cache write is not rolled back,
 *       which is exactly why the eviction has to happen after the commit rather than inside it — see
 *       {@link ProductCacheInvalidation}.
 *   <li><strong>It does not survive a serialisation failure by ignoring it.</strong> An entry that
 *       cannot be read back is dropped and the query is answered from the source; an entry that cannot
 *       be written is a failure of the cache, not of the read, so the value still goes back to the
 *       caller. The asymmetry is deliberate: a cache must never be able to fail a request.
 * </ul>
 */
@Component
public class CachingQueryInterceptor implements QueryInterceptor {

  /** Inside logging and authorization, outside the handler. */
  public static final int ORDER = 100;

  private final QueryCache cache;
  private final ObjectMapper json;
  private final CacheSettings settings;
  private final CacheTelemetry telemetry;

  /**
   * The in-flight fills, one entry per key being filled right now.
   *
   * <p>A {@code CompletableFuture} rather than a lock object, because the follower needs the leader's
   * <em>value</em>, not just permission to run: with a lock, every follower wakes up and re-reads the
   * cache, which works but pays a round trip each and has a window where the leader's write has not
   * landed yet. The owner removes its own entry, so nothing accumulates.
   *
   * <p>It is per-process. Two instances of this service miss the same cold key independently, so single
   * flight bounds the stampede at the number of instances rather than at one. A cross-instance lock
   * would bound it at one and would add a dependency whose own failure mode — a lock nobody releases —
   * stalls every reader of that key. Bounding by instance count is the cheaper trade at this size, and
   * it is a trade, not a fix.
   */
  private final ConcurrentHashMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

  public CachingQueryInterceptor(
      QueryCache cache, ObjectMapper json, CacheSettings settings, CacheTelemetry telemetry) {
    this.cache = cache;
    this.json = json;
    this.settings = settings;
    this.telemetry = telemetry;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R> R intercept(Query<R> query, Invocation<R> invocation) {
    if (!(query instanceof CachedQuery<?>)) {
      // Not cached, and no cost for saying so: an uncached query pays one instanceof.
      return invocation.proceed();
    }
    CachedQuery<R> cached = (CachedQuery<R>) query;
    String key = CacheKeys.current(cached.cacheKey());

    Optional<R> stored = read(key, cached);
    if (stored.isPresent()) {
      telemetry.hit();
      return stored.get();
    }
    telemetry.miss();
    return settings.isSingleFlight()
        ? singleFlight(key, cached, invocation)
        : fill(key, cached, invocation);
  }

  @Override
  public int order() {
    return ORDER;
  }

  /** The plain miss path: run the handler, store what it said. */
  private <R> R fill(String key, CachedQuery<R> query, Invocation<R> invocation) {
    R value = invocation.proceed();
    if (value != null) {
      write(key, value, ttlFor(query));
    }
    return value;
  }

  /**
   * One execution per key, however many callers arrive.
   *
   * <p>The loser of {@code putIfAbsent} waits on the winner's future instead of running the query, which
   * is what turns a stampede on a cold popular key into a single database read. {@code join} wraps a
   * failure in {@link CompletionException}; unwrapping it keeps the exception a caller sees identical to
   * the one they would have seen had they run the query themselves — a follower must not be able to tell
   * that it was a follower.
   */
  @SuppressWarnings("unchecked")
  private <R> R singleFlight(String key, CachedQuery<R> query, Invocation<R> invocation) {
    CompletableFuture<R> mine = new CompletableFuture<>();
    CompletableFuture<R> leader = (CompletableFuture<R>) inFlight.putIfAbsent(key, mine);
    if (leader != null) {
      telemetry.coalesced();
      try {
        return leader.join();
      } catch (CompletionException wrapped) {
        if (wrapped.getCause() instanceof RuntimeException cause) {
          throw cause;
        }
        throw wrapped;
      }
    }
    try {
      R value = fill(key, query, invocation);
      mine.complete(value);
      return value;
    } catch (RuntimeException failure) {
      mine.completeExceptionally(failure);
      throw failure;
    } finally {
      inFlight.remove(key, mine);
    }
  }

  private <R> Optional<R> read(String key, CachedQuery<R> query) {
    Optional<String> stored = cache.get(key);
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(json.readValue(stored.get(), query.resultType()));
    } catch (Exception unreadable) {
      // A shape that no longer parses — the record gained a required component since it was written,
      // most likely, which is what a deploy does to every entry already in the cache. Drop it and
      // answer from the source: the alternative is a read path that fails for as long as the TTL.
      cache.evict(key);
      return Optional.empty();
    }
  }

  private <R> void write(String key, R value, Duration ttl) {
    try {
      cache.put(key, json.writeValueAsString(value), ttl);
    } catch (Exception unwritable) {
      // Not rethrown: the caller already has their answer, and failing their request because a cache
      // would not take a copy of it turns an optimisation into an availability risk. Counted rather
      // than merely swallowed, because a cache that has silently stopped accepting writes looks
      // exactly like a cache with a bad hit ratio, and the two need different people.
      telemetry.writeFailed();
    }
  }

  /** The configured TTL for this query type, spread by jitter. */
  private Duration ttlFor(CachedQuery<?> query) {
    return jittered(settings.ttlFor(query.getClass()));
  }

  /**
   * Spread the expiry, so entries written together do not expire together.
   *
   * <p>This is the avalanche, and it is a consequence of doing everything else right: entries are filled
   * on demand, so a burst of traffic after a deploy fills thousands of keys within a second of each
   * other, and a fixed TTL then expires them all within a second of each other — one round of stampedes
   * per TTL, forever, in lockstep. Single flight bounds each key to one execution; only jitter stops
   * every key from choosing the same moment. They fix different halves of the same problem and neither
   * substitutes for the other.
   *
   * <p>Package-private so {@code TtlJitterTest} can measure the distribution rather than infer it.
   */
  Duration jittered(Duration base) {
    double ratio = settings.getJitterRatio();
    if (ratio <= 0) {
      return base;
    }
    long span = (long) (base.toMillis() * ratio);
    if (span <= 0) {
      return base;
    }
    return base.plusMillis(ThreadLocalRandom.current().nextLong(-span, span + 1));
  }
}
