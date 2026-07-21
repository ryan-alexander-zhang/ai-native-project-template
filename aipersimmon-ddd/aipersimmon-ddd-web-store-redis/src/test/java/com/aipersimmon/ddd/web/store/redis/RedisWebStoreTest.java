package com.aipersimmon.ddd.web.store.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.testsupport.RedisServiceConnection;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimitPolicy;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Exercises the Redis-backed stores against a real Redis via Testcontainers, proving the same
 * semantics as the in-memory and JDBC backends. Skipped when Docker is not available so it never
 * breaks a container-less build.
 */
@Import(RedisServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
@SpringBootTest(classes = RedisWebStoreTest.TestApp.class)
class RedisWebStoreTest {

  @Autowired IdempotencyStore idempotencyStore;
  @Autowired ReplayGuard replayGuard;
  @Autowired RateLimiter rateLimiter;

  @Test
  void idempotencyStoresAndReplays() {
    StoredResponse response =
        new StoredResponse(201, new byte[] {4, 5, 6}, Map.of("Content-Type", "application/json"));

    assertTrue(idempotencyStore.saveIfAbsent("rk1", response, Duration.ofMinutes(10)));
    assertFalse(idempotencyStore.saveIfAbsent("rk1", response, Duration.ofMinutes(10)));

    Optional<StoredResponse> found = idempotencyStore.find("rk1");
    assertTrue(found.isPresent());
    assertEquals(201, found.get().status());
    assertArrayEquals(new byte[] {4, 5, 6}, found.get().body());
    assertEquals("application/json", found.get().headers().get("Content-Type"));
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
