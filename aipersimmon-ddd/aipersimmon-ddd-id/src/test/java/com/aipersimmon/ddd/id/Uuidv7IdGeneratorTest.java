package com.aipersimmon.ddd.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.id.IdGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class Uuidv7IdGeneratorTest {

  private final IdGenerator generator = new Uuidv7IdGenerator();

  @Test
  void producesParseableVersion7Uuids() {
    for (int i = 0; i < 1_000; i++) {
      UUID parsed = UUID.fromString(generator.newId());
      assertEquals(7, parsed.version(), "expected a UUIDv7");
    }
  }

  @Test
  void isStrictlyMonotonicWithinAndAcrossMilliseconds() {
    // The monotonic variant guarantees each id sorts strictly after the previous one, even for a
    // burst minted inside the same millisecond — that ordering is the whole point of UUIDv7.
    String previous = generator.newId();
    for (int i = 0; i < 100_000; i++) {
      String current = generator.newId();
      String last = previous;
      assertTrue(
          current.compareTo(last) > 0,
          () -> "expected strictly increasing ids, but " + current + " <= " + last);
      previous = current;
    }
  }

  @Test
  void isConcurrentlyUnique() throws InterruptedException {
    int threads = 16;
    int perThread = 10_000;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    Set<String> ids = ConcurrentHashMap.newKeySet();
    List<Thread> ignored = new ArrayList<>();
    try {
      for (int t = 0; t < threads; t++) {
        pool.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              for (int i = 0; i < perThread; i++) {
                ids.add(generator.newId());
              }
            });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "generation timed out");
    } finally {
      pool.shutdownNow();
    }
    assertEquals(threads * perThread, ids.size(), "expected no collisions across threads");
  }
}
