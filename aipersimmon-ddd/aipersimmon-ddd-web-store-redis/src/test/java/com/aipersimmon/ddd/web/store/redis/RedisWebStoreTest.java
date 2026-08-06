package com.aipersimmon.ddd.web.store.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.testsupport.RedisServiceConnection;
import com.aipersimmon.ddd.web.spi.IdempotencyClaim;
import com.aipersimmon.ddd.web.spi.IdempotencyKey;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Exercises the Redis-backed stores against a real Redis via Testcontainers, proving the same
 * semantics as the in-memory and MyBatis-Plus backends. Skipped when Docker is not available so it
 * never breaks a container-less build.
 */
@Import(RedisServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
@SpringBootTest(classes = RedisWebStoreTest.TestApp.class)
class RedisWebStoreTest {

  @Autowired IdempotencyStore idempotencyStore;
  @Autowired ReplayGuard replayGuard;
  @Autowired RateLimiter rateLimiter;

  private static final Duration LEASE = Duration.ofMinutes(1);
  private static final Duration RETENTION = Duration.ofMinutes(10);

  private static IdempotencyKey key(String value) {
    return new IdempotencyKey("acme", "alice", value, "fp-" + value);
  }

  @Test
  void aClaimIsWonOnceThenTheOutcomeIsReplayed() {
    IdempotencyKey attempt = key("rk1");
    StoredResponse response =
        new StoredResponse(201, new byte[] {4, 5, 6}, Map.of("Content-Type", "application/json"));

    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));
    // Still executing: neither a second Won (which would duplicate the side effect) nor a Replay.
    assertInstanceOf(IdempotencyClaim.InProgress.class, idempotencyStore.claim(attempt, LEASE));

    idempotencyStore.complete(attempt, response, RETENTION);

    StoredResponse replayed =
        assertInstanceOf(IdempotencyClaim.Replay.class, idempotencyStore.claim(attempt, LEASE))
            .response();
    assertEquals(201, replayed.status());
    assertArrayEquals(new byte[] {4, 5, 6}, replayed.body());
    assertEquals("application/json", replayed.headers().get("Content-Type"));
  }

  @Test
  void oneCallersKeyCannotReachAnothers() {
    IdempotencyKey alice = new IdempotencyKey("acme", "alice", "rk-shared", "fp");
    IdempotencyKey bob = new IdempotencyKey("acme", "bob", "rk-shared", "fp");

    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(alice, LEASE));
    idempotencyStore.complete(alice, new StoredResponse(201, new byte[0], Map.of()), RETENTION);

    assertInstanceOf(
        IdempotencyClaim.Won.class,
        idempotencyStore.claim(bob, LEASE),
        "bob's key must not resolve to alice's stored response");
  }

  @Test
  void abandoningReleasesAClaimButNeverACompletedOutcome() {
    IdempotencyKey attempt = key("rk-abandon");

    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));
    idempotencyStore.abandon(attempt);
    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(attempt, LEASE));

    idempotencyStore.complete(attempt, new StoredResponse(202, new byte[0], Map.of()), RETENTION);
    idempotencyStore.abandon(attempt);
    assertInstanceOf(
        IdempotencyClaim.Replay.class,
        idempotencyStore.claim(attempt, LEASE),
        "a late abandon must not delete a replayable outcome");
  }

  @Test
  void reusingAKeyForADifferentRequestIsRefused() {
    IdempotencyKey first = new IdempotencyKey("acme", "alice", "rk-mismatch", "fingerprint-a");
    assertInstanceOf(IdempotencyClaim.Won.class, idempotencyStore.claim(first, LEASE));

    IdempotencyKey different = new IdempotencyKey("acme", "alice", "rk-mismatch", "fingerprint-b");
    assertInstanceOf(IdempotencyClaim.Mismatch.class, idempotencyStore.claim(different, LEASE));
  }

  @Test
  void replayGuardDetectsReuse() {
    assertFalse(replayGuard.seenBefore("rn1", Duration.ofMinutes(5)));
    assertTrue(replayGuard.seenBefore("rn1", Duration.ofMinutes(5)));
  }

  @Test
  void rateLimiterEnforcesLimit() {
    RateLimitPolicy policy = new RateLimitPolicy("test", 2, Duration.ofMinutes(1));
    assertTrue(rateLimiter.tryAcquire("rip-1", policy).allowed());
    assertTrue(rateLimiter.tryAcquire("rip-1", policy).allowed());
    assertFalse(rateLimiter.tryAcquire("rip-1", policy).allowed());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {}
}
