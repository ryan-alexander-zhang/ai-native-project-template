package com.aipersimmon.ddd.web.store.redis;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed {@link IdempotencyStore}: the first response is stored as JSON under a per-key entry
 * with a native TTL, and {@link #saveIfAbsent} uses {@code SET NX} for atomic first-writer-wins
 * across instances.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

  private static final String PREFIX = "aipersimmon:web:idem:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<StoredResponse> find(String key) {
    String json = redis.opsForValue().get(PREFIX + key);
    if (json == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(json, StoredResponse.class));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize stored idempotent response", e);
    }
  }

  @Override
  public boolean saveIfAbsent(String key, StoredResponse response, Duration ttl) {
    String json;
    try {
      json = objectMapper.writeValueAsString(response);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize idempotent response", e);
    }
    Boolean stored = redis.opsForValue().setIfAbsent(PREFIX + key, json, ttl);
    return Boolean.TRUE.equals(stored);
  }
}
