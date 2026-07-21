package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.ReplayGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ReplayGuard} for single-node and development use. A nonce is remembered until
 * its expiry; reuse within that window is reported as seen. Not suitable for multiple instances — a
 * store backend module replaces this bean.
 */
public class InMemoryReplayGuard implements ReplayGuard {

  private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryReplayGuard(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean seenBefore(String nonce, Duration ttl) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    Instant previous =
        seen.compute(
            nonce, (k, current) -> (current != null && current.isAfter(now)) ? current : expiresAt);
    // If the stored expiry is the one we just computed, this was the first sighting.
    return previous != expiresAt;
  }
}
