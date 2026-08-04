package com.example.samples.s26.catalog.infrastructure;

import com.example.samples.s26.catalog.application.QueryCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The cache, in Redis, and nothing else.
 *
 * <p>Strings rather than a serialised object graph, because the entry's shape is a decision the application
 * already made (Jackson, in the interceptor) and encoding it twice would put half the format here. It also
 * means an operator can read an entry with {@code GET} during an incident, which is worth more than it
 * sounds when the question is "what exactly is this thing serving".
 *
 * <p>Every write has a TTL, with no path that forgets one — {@link #put} takes it as a parameter rather than
 * defaulting it. An entry with no expiry is not a cache entry; it is a copy of the database that will
 * outlive every attempt to correct it, and one lost eviction makes it permanently wrong.
 */
@Component
class RedisQueryCache implements QueryCache {

  /**
   * How many keys a scan asks for per round trip. Small enough not to hold the server, large enough that a
   * tenant-wide flush is not thousands of round trips.
   */
  private static final int SCAN_BATCH = 256;

  private final StringRedisTemplate redis;

  RedisQueryCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Optional<String> get(String key) {
    return Optional.ofNullable(redis.opsForValue().get(key));
  }

  @Override
  public void put(String key, String value, Duration ttl) {
    redis.opsForValue().set(key, value, ttl);
  }

  @Override
  public void evict(String key) {
    redis.delete(key);
  }

  /**
   * {@code SCAN}, not {@code KEYS}.
   *
   * <p>Both answer "which keys match this pattern". {@code KEYS} does it in one command that walks the whole
   * keyspace with the server's single thread held, so on a shared Redis with millions of keys it is an
   * outage for everyone using that instance — including the service that issued it. {@code SCAN} does it in
   * cursored batches that interleave with other traffic, at the price of a weaker guarantee: keys added or
   * removed while it runs may or may not appear. For an eviction sweep that weakness costs nothing, since a
   * key created after the flush began is one that was filled from post-flush state anyway.
   *
   * <p>{@code deleteCount} rather than a delete per key: one round trip for the batch.
   */
  @Override
  public int evictMatching(String pattern) {
    ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build();
    int removed = 0;
    List<String> batch = new ArrayList<>(SCAN_BATCH);
    try (Cursor<String> keys = redis.scan(options)) {
      while (keys.hasNext()) {
        batch.add(keys.next());
        if (batch.size() == SCAN_BATCH) {
          removed += delete(batch);
          batch.clear();
        }
      }
    }
    removed += delete(batch);
    return removed;
  }

  @Override
  public Optional<Duration> timeToLive(String key) {
    Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
    // Redis answers -2 for "no such key" and -1 for "no expiry". Neither is a duration, and conflating
    // them with 0 would report an entry that never expires as one that just did.
    if (millis == null || millis < 0) {
      return Optional.empty();
    }
    return Optional.of(Duration.ofMillis(millis));
  }

  private int delete(List<String> keys) {
    if (keys.isEmpty()) {
      return 0;
    }
    Long deleted = redis.delete(keys);
    return deleted == null ? 0 : deleted.intValue();
  }
}
