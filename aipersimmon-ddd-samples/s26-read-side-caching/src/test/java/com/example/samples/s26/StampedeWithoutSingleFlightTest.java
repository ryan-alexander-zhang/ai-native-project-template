package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The same ten callers with single flight switched off — the naive cache, and what it costs.
 *
 * <p>The sibling of {@link StampedeWithSingleFlightTest}, and the reason that test means something. A
 * collapsed stampede asserted alone could be a stampede that never happened; this measures the same
 * arrangement without the collapsing and finds all ten callers at the source.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "s26.cache.single-flight=false")
class StampedeWithoutSingleFlightTest extends CacheTestBase {

  private static final int CALLERS = 10;

  /**
   * Ten misses, ten reads — and the popular key is the one it happens to.
   *
   * <p>Worth being precise about which keys this hurts: the more requested a key is, the more callers arrive
   * inside the window in which it is cold, so the cost falls hardest exactly where the cache was supposed to
   * help most. A key nobody asks for twice never stampedes.
   *
   * <p>Note also that all ten answers are identical and correct. The naive cache is not <em>wrong</em>; it is
   * expensive at the worst possible moment, which is why this is a load problem rather than a correctness one
   * and why it survives code review so easily.
   */
  @Test
  void tensimultaneousMissesCostTenReads() throws Exception {
    gatedReads.expect(CALLERS, 400);

    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(CALLERS);
    List<String> answers = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();

    for (int i = 0; i < CALLERS; i++) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  start.await(10, TimeUnit.SECONDS);
                  String name = detail(KEYBOARD).name();
                  synchronized (answers) {
                    answers.add(name);
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  finished.countDown();
                }
              },
              "s26-caller-" + i);
      threads.add(thread);
      thread.start();
    }

    start.countDown();
    assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    for (Thread thread : threads) {
      thread.join();
    }

    assertThat(answers).hasSize(CALLERS).containsOnly("Keyboard");
    assertThat(gatedReads.arrivals()).isEqualTo(CALLERS);
    assertThat(telemetry.getDatabaseReads()).isEqualTo(CALLERS);
    assertThat(telemetry.getCoalesced()).isZero();
  }
}
