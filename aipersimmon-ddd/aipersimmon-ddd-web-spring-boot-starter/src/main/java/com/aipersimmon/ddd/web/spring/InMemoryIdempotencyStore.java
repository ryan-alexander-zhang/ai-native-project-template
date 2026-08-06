package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.IdempotencyClaim;
import com.aipersimmon.ddd.web.spi.IdempotencyKey;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyStore} for single-node and development use. Not suitable for multiple
 * instances — each JVM would hold its own claims, so the same key could execute once per instance;
 * use a store backend module ({@code -web-store-redis}/{@code -web-store-mybatis-plus}) in
 * production, which replaces this bean.
 *
 * <p>Atomicity comes from {@link ConcurrentHashMap#compute}, which holds the bin lock for the key
 * while the state transition is decided — the local equivalent of the conditional insert the
 * durable backends use.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

  private record Entry(String fingerprint, StoredResponse response, Instant expiresAt) {

    boolean pending() {
      return response == null;
    }
  }

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryIdempotencyStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public IdempotencyClaim claim(IdempotencyKey key, Duration leaseTtl) {
    Instant now = clock.instant();
    Entry claimed = new Entry(key.fingerprint(), null, now.plus(leaseTtl));
    Entry current =
        entries.compute(
            mapKey(key),
            (k, existing) -> {
              // An entry past its deadline is not evidence of anything: a completed outcome has
              // outlived the retry window, and a claim whose lease ran out belongs to an attempt
              // that
              // never came back. Either way the key is free again.
              if (existing == null || !existing.expiresAt().isAfter(now)) {
                return claimed;
              }
              return existing;
            });
    if (current == claimed) {
      return new IdempotencyClaim.Won();
    }
    if (!current.fingerprint().equals(key.fingerprint())) {
      return new IdempotencyClaim.Mismatch();
    }
    return current.pending()
        ? new IdempotencyClaim.InProgress()
        : new IdempotencyClaim.Replay(current.response());
  }

  @Override
  public void complete(IdempotencyKey key, StoredResponse response, Duration ttl) {
    entries.put(mapKey(key), new Entry(key.fingerprint(), response, clock.instant().plus(ttl)));
  }

  @Override
  public void abandon(IdempotencyKey key) {
    entries.computeIfPresent(mapKey(key), (k, existing) -> existing.pending() ? null : existing);
  }

  /**
   * The identity triple flattened into one map key. NUL separates the segments so no combination of
   * tenant, principal and key can spell out another.
   */
  private static String mapKey(IdempotencyKey key) {
    return key.tenant() + '\0' + key.principal() + '\0' + key.key();
  }
}
