package com.aipersimmon.ddd.saga.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.saga.Deadline;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Verifies the in-process deadline scheduler fires a due deadline to the handler and that
 * cancelling a pending deadline prevents it from firing.
 */
class SchedulingDeadlineSchedulerTest {

  private ThreadPoolTaskScheduler taskScheduler;

  @BeforeEach
  void startScheduler() {
    taskScheduler = new ThreadPoolTaskScheduler();
    taskScheduler.setPoolSize(1);
    taskScheduler.initialize();
  }

  @AfterEach
  void stopScheduler() {
    taskScheduler.shutdown();
  }

  @Test
  void firesDueDeadlineToTheHandler() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Deadline> fired = new AtomicReference<>();
    SchedulingDeadlineScheduler scheduler =
        new SchedulingDeadlineScheduler(
            taskScheduler,
            () ->
                d -> {
                  fired.set(d);
                  latch.countDown();
                });

    Deadline deadline = new Deadline("order-1", "confirm-timeout", Instant.now().plusMillis(100));
    scheduler.schedule(deadline);

    assertTrue(latch.await(2, TimeUnit.SECONDS), "deadline should have fired");
    assertEquals(deadline, fired.get());
  }

  @Test
  void cancelPreventsAPendingDeadlineFromFiring() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    SchedulingDeadlineScheduler scheduler =
        new SchedulingDeadlineScheduler(taskScheduler, () -> d -> latch.countDown());

    scheduler.schedule(new Deadline("order-1", "confirm-timeout", Instant.now().plusMillis(300)));
    scheduler.cancel("order-1", "confirm-timeout");

    assertFalse(latch.await(600, TimeUnit.MILLISECONDS), "cancelled deadline must not fire");
  }
}
