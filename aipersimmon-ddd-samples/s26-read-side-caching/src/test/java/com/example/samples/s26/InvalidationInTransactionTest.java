package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evicting inside the transaction, and the stale entry that outlives the write.
 *
 * <p>This is the shape somebody reaches for first — {@code @EventListener} is shorter than
 * {@code @TransactionalEventListener}, fires immediately, and in every test that does not interleave a reader
 * it is indistinguishable from the correct one. So the second test here is that indistinguishable case, and
 * the first is what it hides.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "s26.cache.invalidate=IN_TRANSACTION")
class InvalidationInTransactionTest extends CacheTestBase {

  @Autowired private PlatformTransactionManager transactions;

  /**
   * The eviction runs, and the cache is wrong anyway — for a whole TTL.
   *
   * <ol>
   *   <li>The entry is warm, holding {@code Keyboard}.
   *   <li>A writer renames the product and the eager listener drops the entry, still inside the transaction.
   *   <li>A reader misses, reads the database — which still shows {@code Keyboard}, because the writer has not
   *       committed — and stores it.
   *   <li>The writer commits.
   * </ol>
   *
   * <p>The eviction has already happened and will not happen again, so the value it was supposed to remove is
   * now the cached one. The last two assertions are the ones worth reading together: one eviction was
   * recorded, and the cache is still serving the old name. A counter showing evictions is not evidence that
   * the cache is correct.
   */
  @Test
  void areaderRefillsFromTheUncommittedStateAndTheStaleValueSurvives() throws Exception {
    detail(KEYBOARD);
    assertThat(cache.get(keyOf(KEYBOARD))).isPresent();

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

    assertThat(readerSaw.get()).isEqualTo("Keyboard");
    assertThat(storedName(KEYBOARD)).isEqualTo("Mechanical Keyboard");

    // The damage: an eviction was performed, and the cache is serving the value it evicted.
    assertThat(telemetry.getEvictions()).isEqualTo(1);
    assertThat(detail(KEYBOARD).name()).isEqualTo("Keyboard");
    assertThat(cache.timeToLive(keyOf(KEYBOARD))).isPresent();
  }

  /**
   * And with nobody reading concurrently, the wrong listener is perfectly convincing.
   *
   * <p>The sibling assertion, and the reason the test above had to be written with latches. Without an
   * interleaving there is no way to tell the two configurations apart from the outside — which is exactly why
   * a suite of tests like this one alone would have shipped the bug.
   */
  @Test
  void withoutAConcurrentReaderItLooksCorrect() {
    detail(KEYBOARD);

    rename(KEYBOARD, "Mechanical Keyboard");

    assertThat(detail(KEYBOARD).name()).isEqualTo("Mechanical Keyboard");
  }
}
