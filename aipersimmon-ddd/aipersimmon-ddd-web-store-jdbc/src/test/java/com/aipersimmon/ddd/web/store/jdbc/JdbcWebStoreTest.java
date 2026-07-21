package com.aipersimmon.ddd.web.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * Exercises the JDBC-backed stores against H2 with a controllable clock: the same semantics the
 * in-memory defaults have, plus TTL expiry and rate-limit window rollover driven by advancing the
 * clock.
 */
@SpringBootTest(classes = JdbcWebStoreTest.TestApp.class)
class JdbcWebStoreTest {

  @Autowired IdempotencyStore idempotencyStore;
  @Autowired ReplayGuard replayGuard;
  @Autowired RateLimiter rateLimiter;
  @Autowired MutableClock clock;

  @Test
  void idempotencyStoresReplaysAndExpires() {
    StoredResponse response =
        new StoredResponse(201, new byte[] {1, 2, 3}, Map.of("Content-Type", "application/json"));

    assertTrue(idempotencyStore.saveIfAbsent("k1", response, Duration.ofHours(1)));
    assertFalse(
        idempotencyStore.saveIfAbsent("k1", response, Duration.ofHours(1)), "second save loses");

    Optional<StoredResponse> found = idempotencyStore.find("k1");
    assertTrue(found.isPresent());
    assertEquals(201, found.get().status());
    assertArrayEquals(new byte[] {1, 2, 3}, found.get().body());
    assertEquals("application/json", found.get().headers().get("Content-Type"));

    clock.advance(Duration.ofHours(2));
    assertTrue(idempotencyStore.find("k1").isEmpty(), "entry expired");
    assertTrue(
        idempotencyStore.saveIfAbsent("k1", response, Duration.ofHours(1)),
        "expired key re-savable");
  }

  @Test
  void replayGuardDetectsReuseUntilExpiry() {
    assertFalse(replayGuard.seenBefore("n1", Duration.ofMinutes(5)), "first sighting");
    assertTrue(replayGuard.seenBefore("n1", Duration.ofMinutes(5)), "reuse detected");

    clock.advance(Duration.ofMinutes(6));
    assertFalse(replayGuard.seenBefore("n1", Duration.ofMinutes(5)), "nonce expired, fresh again");
  }

  @Test
  void rateLimiterCountsWithinWindowAndResets() {
    RateLimitPolicy policy = new RateLimitPolicy("test", 2, Duration.ofMinutes(1));

    assertTrue(rateLimiter.tryAcquire("ip-1", policy).allowed());
    assertTrue(rateLimiter.tryAcquire("ip-1", policy).allowed());
    RateLimiter.Decision third = rateLimiter.tryAcquire("ip-1", policy);
    assertFalse(third.allowed(), "third request over limit of 2");
    assertTrue(third.retryAfter().toMillis() > 0);

    clock.advance(Duration.ofMinutes(1));
    assertTrue(rateLimiter.tryAcquire("ip-1", policy).allowed(), "new window resets the count");
  }

  static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      this.instant = this.instant.plus(duration);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    MutableClock aipersimmonDddWebStoreClock() {
      return new MutableClock(Instant.parse("2026-01-01T00:00:30Z"));
    }
  }
}
