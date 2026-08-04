package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Ten callers, one cold key, one execution. The default configuration. */
class StampedeWithSingleFlightTest extends CacheTestBase {

  private static final int CALLERS = 10;

  /**
   * One trip to the source for ten simultaneous misses, and nine callers told to wait for it.
   *
   * <p>The gate lets exactly one caller reach the source and holds it there; the other nine never arrive,
   * because they are waiting on the leader's future rather than running the query. What they get back is the
   * leader's value, indistinguishable from having computed it themselves.
   *
   * <p>{@code coalesced} is the number that makes this visible in production. Without it, a cache that
   * collapses stampedes and a cache that never has any look identical from the metrics.
   */
  @Test
  void tensimultaneousMissesCostOneRead() throws Exception {
    gatedReads.expect(CALLERS, 400);

    List<String> answers = askConcurrently(CALLERS);

    assertThat(answers).hasSize(CALLERS).containsOnly("Keyboard");
    assertThat(gatedReads.arrivals()).isEqualTo(1);
    assertThat(telemetry.getDatabaseReads()).isEqualTo(1);
    assertThat(telemetry.getCoalesced()).isEqualTo(CALLERS - 1L);
  }

  /**
   * And once it is warm, nobody is coalescing anything.
   *
   * <p>The control for the count above: {@code coalesced} must be zero when there is nothing to collapse, or
   * the counter is measuring arrivals rather than waits.
   */
  @Test
  void awarmKeyCoalescesNothing() throws Exception {
    detail(KEYBOARD);
    telemetry.reset();

    List<String> answers = askConcurrently(CALLERS);

    assertThat(answers).hasSize(CALLERS).containsOnly("Keyboard");
    assertThat(telemetry.getHits()).isEqualTo(CALLERS);
    assertThat(telemetry.getCoalesced()).isZero();
    assertThat(telemetry.getDatabaseReads()).isZero();
  }

  private List<String> askConcurrently(int callers) throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(callers);
    List<String> answers = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();

    for (int i = 0; i < callers; i++) {
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
    return answers;
  }
}
