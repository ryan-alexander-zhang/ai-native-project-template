package com.aipersimmon.ddd.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.fasterxml.uuid.UUIDClock;
import java.util.ArrayList;
import java.util.HashSet;
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

  /** A clock a test moves by hand, so time is an input rather than a race. */
  private static final class SteppingClock extends UUIDClock {
    private long millis = 1_760_000_000_000L;

    @Override
    public long currentTimeMillis() {
      return millis;
    }

    void advance(long by) {
      millis += by;
    }
  }

  @Test
  void aBurstInsideOneMillisecondStaysStrictlyOrdered() {
    // The whole point of choosing v7: a burst minted faster than the clock ticks must not scatter
    // across the index. Inside one millisecond the generator increments the previous value's
    // entropy rather than drawing fresh — this exercises that counter well past a byte rollover.
    IdGenerator fixedClock = new Uuidv7IdGenerator(new SteppingClock());

    String previous = fixedClock.newId();
    for (int i = 0; i < 100_000; i++) {
      String current = fixedClock.newId();
      String last = previous;
      assertTrue(
          current.compareTo(last) > 0,
          () ->
              "expected strictly increasing ids inside one millisecond, but "
                  + current
                  + " <= "
                  + last);
      previous = current;
    }
  }

  @Test
  void anIdMintedInALaterMillisecondSortsAfterAnEarlierOne() {
    SteppingClock clock = new SteppingClock();
    IdGenerator generator = new Uuidv7IdGenerator(clock);

    String earlier = generator.newId();
    clock.advance(1);
    String later = generator.newId();

    assertTrue(later.compareTo(earlier) > 0, later + " should sort after " + earlier);
  }

  @Test
  void aClockThatStepsBackwardsMintsIdsThatSortEarlier() {
    SteppingClock clock = new SteppingClock();
    IdGenerator generator = new Uuidv7IdGenerator(clock);
    String beforeTheStep = generator.newId();

    clock.advance(-1);
    String afterTheStep = generator.newId();

    // Asserted rather than avoided. An NTP correction or a resumed VM moves the wall clock back
    // and these ids follow it — which used to surface as a rare red build, from a test claiming a
    // monotonicity no wall-clock generator can promise. It costs a moment of index locality and
    // nothing else: nothing in this framework orders by an id.
    assertTrue(
        afterTheStep.compareTo(beforeTheStep) < 0,
        "a backwards clock step is expected to produce an earlier-sorting id");
  }

  @Test
  void aClockThatStepsBackwardsStillMintsDistinctIds() {
    SteppingClock clock = new SteppingClock();
    IdGenerator generator = new Uuidv7IdGenerator(clock);
    Set<String> ids = new HashSet<>();

    for (int step = 0; step < 100; step++) {
      ids.add(generator.newId());
      clock.advance(step % 2 == 0 ? -1 : 1);
    }

    // The property that actually matters when the clock misbehaves. Uniqueness comes from the
    // entropy, redrawn on any timestamp change, so it does not depend on time moving forwards.
    assertEquals(100, ids.size(), "ids must stay unique however the clock moves");
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
