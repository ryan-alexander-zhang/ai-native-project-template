package com.aipersimmon.ddd.web.store.redis;

import com.aipersimmon.ddd.web.spi.ReplayGuard;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed {@link ReplayGuard}: {@code SET NX} with a TTL records a nonce on first sight; a
 * failed set (the key already exists) means the nonce was seen before. The single-use guarantee
 * holds across instances.
 */
public class RedisReplayGuard implements ReplayGuard {

  private static final String PREFIX = "aipersimmon:web:nonce:";

  private final StringRedisTemplate redis;

  public RedisReplayGuard(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public boolean seenBefore(String nonce, Duration ttl) {
    Boolean stored = redis.opsForValue().setIfAbsent(PREFIX + nonce, "1", ttl);
    return !Boolean.TRUE.equals(stored);
  }
}
