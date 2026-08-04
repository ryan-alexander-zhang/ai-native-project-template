package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evicting after the commit, and the exact extent of what that buys.
 *
 * <p>Two tests, deliberately in that order: the ordering that the eager listener gets wrong, and the ordering
 * that this one still gets wrong. The second is the more important of the two, because a team that has moved
 * its eviction to {@code AFTER_COMMIT} tends to consider the problem solved and set the TTL to something
 * generous.
 */
class InvalidationAfterCommitTest extends CacheTestBase {

  @Autowired private PlatformTransactionManager transactions;

  /**
   * A reader that was already looking cannot resurrect the old value.
   *
   * <p>The interleaving is the one that breaks the eager listener: a reader runs while the writer's
   * transaction is open, so everything it can see is the pre-commit state. Here the entry is still in the
   * cache at that moment (nothing has been evicted yet), so the reader is served the old value — which is
   * correct, because at that instant the old value <em>is</em> the committed truth — and the eviction happens
   * afterwards, on the committing thread, once the new value is visible to everybody.
   */
  @Test
  void areaderInFlightCannotUndoTheEviction() throws Exception {
    detail(KEYBOARD);

    CountDownLatch readerMayRun = new CountDownLatch(1);
    CountDownLatch readerDone = new CountDownLatch(1);
    AtomicReference<String> readerSaw = new AtomicReference<>();

    Thread reader =
        new Thread(
            () -> {
              try {
                readerMayRun.await(10, TimeUnit.SECONDS);
                readerSaw.set(detail(KEYBOARD).name());
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                readerDone.countDown();
              }
            },
            "s26-reader");
    reader.start();

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              rename(KEYBOARD, "Mechanical Keyboard");
              readerMayRun.countDown();
              try {
                readerDone.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    reader.join();

    // What the reader saw was true when it asked.
    assertThat(readerSaw.get()).isEqualTo("Keyboard");
    // And the next reader gets the truth as it is now.
    assertThat(storedName(KEYBOARD)).isEqualTo("Mechanical Keyboard");
    assertThat(detail(KEYBOARD).name()).isEqualTo("Mechanical Keyboard");
  }

  /**
   * The residual window: an eviction can still be overtaken by a read that started before it.
   *
   * <p>Constructed exactly, with a real read and a real value: the reader misses, reads the pre-commit
   * database, and is then parked immediately before storing what it found. The writer commits and evicts —
   * removing nothing, because the entry is not there yet. Then the reader's store lands, and the cache now
   * holds a value that the eviction was supposed to have prevented.
   *
   * <p>This cannot be fixed by moving the eviction, because there is no moment at which it could run that is
   * after every read that has already begun. Closing it needs either a distributed transaction across
   * Postgres and Redis, or a version stamp on the entry so a late write can be recognised as late — both real
   * options, both more machinery than most read models are worth. What is left is the TTL, which is why an
   * unbounded one is not a tuning choice: it is the difference between "wrong for a minute" and "wrong until
   * someone notices".
   */
  @Test
  void anevictionCanStillBeOvertakenByALatePut() throws Exception {
    detail(KEYBOARD);
    cache.evict(keyOf(KEYBOARD));

    CountDownLatch openTheGate = new CountDownLatch(1);
    CountDownLatch readerIsParked = new CountDownLatch(1);
    controlledCache.holdPutsUntil(openTheGate, readerIsParked);

    Thread reader = new Thread(() -> detail(KEYBOARD), "s26-late-writer");
    reader.start();

    assertThat(readerIsParked.await(10, TimeUnit.SECONDS)).isTrue();

    rename(KEYBOARD, "Mechanical Keyboard");
    // The eviction has already run at this point, and found nothing to remove.
    assertThat(cache.get(keyOf(KEYBOARD))).isEmpty();
    assertThat(telemetry.getEvictions()).isEqualTo(1);

    openTheGate.countDown();
    reader.join();
    controlledCache.reset();

    // A stale entry, created after the eviction that was meant to prevent it.
    assertThat(cache.get(keyOf(KEYBOARD))).isPresent();
    assertThat(storedName(KEYBOARD)).isEqualTo("Mechanical Keyboard");
    assertThat(detail(KEYBOARD).name()).isEqualTo("Keyboard");
    // And the only thing that will ever correct it.
    assertThat(cache.timeToLive(keyOf(KEYBOARD))).isPresent();
  }
}
