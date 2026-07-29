package com.aipersimmon.ddd.web.store.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.web.spi.IdempotencyClaim;
import com.aipersimmon.ddd.web.spi.IdempotencyKey;
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

  private static final Duration LEASE = Duration.ofMinutes(1);
  private static final Duration RETENTION = Duration.ofHours(1);

  private static IdempotencyKey key(String tenant, String principal, String value) {
    return new IdempotencyKey(tenant, principal, value, "fp-" + value);
  }

  @Test
  void theSameKeyUnderTwoTenantsAreTwoClaims() {
    // Two tenants send the SAME client-provided Idempotency-Key; each must keep its own outcome
    // (the
    // composite (tenant_id, principal, idempotency_key) PK), never reading back the other's.
    IdempotencyKey acme = key("acme", "", "shared-key");
    IdempotencyKey globex = key("globex", "", "shared-key");

    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(acme, LEASE));
    idempotencyStore.complete(acme, new StoredResponse(201, new byte[] {1}, Map.of()), RETENTION);

    // globex reusing the key is a first attempt in its own namespace — it wins, not a duplicate.
    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(globex, LEASE));
    idempotencyStore.complete(globex, new StoredResponse(202, new byte[] {2}, Map.of()), RETENTION);

    assertEquals(201, replayOf(acme).status());
    assertEquals(202, replayOf(globex).status());
    // A third tenant that never used the key gets a claim of its own, not someone else's answer.
    assertInstanceOf(
        IdempotencyClaim.Won.class,
        idempotencyStore.claim(key("initech", "", "shared-key"), LEASE));
  }

  @Test
  void theSameKeyUnderTwoPrincipalsAreTwoClaims() {
    // The key is a value a caller invents. Without the principal in its identity, presenting a key
    // someone else used returns THEIR response body — so alice's outcome must be unreachable to bob
    // even within one tenant.
    IdempotencyKey alice = key("acme", "alice", "per-principal-key");
    IdempotencyKey bob = key("acme", "bob", "per-principal-key");

    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(alice, LEASE));
    idempotencyStore.complete(
        alice, new StoredResponse(201, "alice's account".getBytes(UTF_8), Map.of()), RETENTION);

    assertInstanceOf(
        IdempotencyClaim.Won.class,
        idempotencyStore.claim(bob, LEASE),
        "bob's key must not resolve to alice's stored response");
  }

  @Test
  void aSecondAttemptWhileTheFirstIsInFlightIsToldSoRatherThanExecuting() {
    IdempotencyKey attempt = key("acme", "", "in-flight-key");

    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));
    // No outcome recorded yet: the honest answer is "in progress", not a second Won (which would
    // run
    // the side effect twice) and not a Replay (there is nothing to replay).
    assertInstanceOf(IdempotencyClaim.InProgress.class, idempotencyStore.claim(attempt, LEASE));
  }

  @Test
  void aCompletedOutcomeIsReplayedVerbatimUntilItExpires() {
    IdempotencyKey attempt = key("acme", "", "replay-key");
    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));
    idempotencyStore.complete(
        attempt,
        new StoredResponse(201, new byte[] {1, 2, 3}, Map.of("Content-Type", "application/json")),
        RETENTION);

    StoredResponse replayed = replayOf(attempt);
    assertEquals(201, replayed.status());
    assertArrayEquals(new byte[] {1, 2, 3}, replayed.body());
    assertEquals("application/json", replayed.headers().get("Content-Type"));

    clock.advance(Duration.ofHours(2));
    assertInstanceOf(
        IdempotencyClaim.Won.class,
        idempotencyStore.claim(attempt, LEASE),
        "past the retry window the key is free again");
  }

  @Test
  void aClaimWhoseLeaseRanOutIsTakenOver() {
    IdempotencyKey attempt = key("acme", "", "lease-key");
    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));

    // The holder died mid-request: nothing will ever complete this claim. Once the lease passes the
    // key has to become usable again, or one crash would burn it for the whole retention window.
    clock.advance(LEASE.plusSeconds(1));
    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));
  }

  @Test
  void abandoningReleasesAClaimButNeverACompletedOutcome() {
    IdempotencyKey attempt = key("acme", "", "abandon-key");

    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));
    idempotencyStore.abandon(attempt);
    assertInstanceOf(
        IdempotencyClaim.Won.class,
        idempotencyStore.claim(attempt, LEASE),
        "an abandoned claim leaves the key free");

    idempotencyStore.complete(attempt, new StoredResponse(201, new byte[0], Map.of()), RETENTION);
    idempotencyStore.abandon(attempt);
    assertEquals(
        201, replayOf(attempt).status(), "a late abandon must not delete a replayable outcome");
  }

  @Test
  void reusingAKeyForADifferentRequestIsRefusedRatherThanAnswered() {
    IdempotencyKey first =
        new IdempotencyKey("acme", "", "mismatch-key", "fingerprint-of-request-a");
    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(first, LEASE));
    idempotencyStore.complete(first, new StoredResponse(201, new byte[0], Map.of()), RETENTION);

    IdempotencyKey different =
        new IdempotencyKey("acme", "", "mismatch-key", "fingerprint-of-request-b");
    assertInstanceOf(
        IdempotencyClaim.Mismatch.class,
        idempotencyStore.claim(different, LEASE),
        "neither executing nor replaying is right when the key names another request");
  }

  private StoredResponse replayOf(IdempotencyKey key) {
    IdempotencyClaim claim = idempotencyStore.claim(key, LEASE);
    return assertInstanceOf(IdempotencyClaim.Replay.class, claim).response();
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
