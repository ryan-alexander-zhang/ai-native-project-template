package com.aipersimmon.ddd.web.store.redis;

import com.aipersimmon.ddd.web.spi.IdempotencyClaim;
import com.aipersimmon.ddd.web.spi.IdempotencyKey;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed {@link IdempotencyStore}.
 *
 * <p>A claim is {@code SET NX} of a pending marker carrying the request fingerprint, which makes
 * the winner unambiguous across instances in one round trip; the marker's TTL is the claim lease,
 * so an attempt that dies mid-request frees the key without anything having to sweep it. Completing
 * rewrites the same entry with the outcome and the (much longer) retention TTL.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

  private static final String PREFIX = "aipersimmon:web:idem:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /**
   * The stored entry. {@code response} is absent while the claim is pending, which is what
   * distinguishes "someone is working on this" from "here is the answer".
   */
  private record Entry(String fingerprint, StoredResponse response) {}

  @Override
  public IdempotencyClaim claim(IdempotencyKey key, Duration leaseTtl) {
    String entryKey = entryKey(key);
    Boolean won =
        redis
            .opsForValue()
            .setIfAbsent(entryKey, write(new Entry(key.fingerprint(), null)), leaseTtl);
    if (Boolean.TRUE.equals(won)) {
      return new IdempotencyClaim.Won();
    }
    String json = redis.opsForValue().get(entryKey);
    // The entry can expire between losing the race and reading it. There is nothing to report then,
    // so
    // say in progress: the caller retries rather than executing on a guess.
    if (json == null) {
      return new IdempotencyClaim.InProgress();
    }
    Entry existing = read(json);
    if (!existing.fingerprint().equals(key.fingerprint())) {
      return new IdempotencyClaim.Mismatch();
    }
    return existing.response() == null
        ? new IdempotencyClaim.InProgress()
        : new IdempotencyClaim.Replay(existing.response());
  }

  @Override
  public void complete(IdempotencyKey key, StoredResponse response, Duration ttl) {
    redis.opsForValue().set(entryKey(key), write(new Entry(key.fingerprint(), response)), ttl);
  }

  @Override
  public void abandon(IdempotencyKey key) {
    String entryKey = entryKey(key);
    String json = redis.opsForValue().get(entryKey);
    // Only release a pending claim. A completed outcome is one a client is entitled to replay, and
    // a
    // late abandon must not delete it.
    if (json != null && read(json).response() == null) {
      redis.delete(entryKey);
    }
  }

  /**
   * The identity triple as a Redis key. The tenant and principal segments are what stop one
   * caller's key from addressing another's entry; the client-supplied key comes last so it cannot
   * spell out a different tenant or principal by containing a separator.
   */
  private static String entryKey(IdempotencyKey key) {
    return PREFIX + key.tenant() + ':' + key.principal() + ':' + key.key();
  }

  private String write(Entry entry) {
    try {
      return objectMapper.writeValueAsString(entry);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialise idempotency entry", e);
    }
  }

  private Entry read(String json) {
    try {
      return objectMapper.readValue(json, Entry.class);
    } catch (Exception e) {
      // An entry that cannot be read back is unusable, and answering a retry with a partially
      // understood response would be worse than failing. The JDBC backend fails the same way for
      // the
      // same reason — the two must not disagree here.
      throw new IllegalStateException("stored idempotency entry is unreadable", e);
    }
  }
}
