package com.aipersimmon.ddd.outbox.engine.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

/**
 * The scheduled trigger does one thing, and — just as importantly — does not coordinate instances.
 * Mutual exclusion moved onto the row, so every instance polls and they take disjoint work. A lock
 * here is what once stopped all delivery for as long as an hour when the instance holding it was
 * killed, because the survivors skipped their polls rather than taking over.
 */
class OutboxRelaySchedulerTest {

  /** A relay that only counts, so the trigger can be tested without a store or a transport. */
  private static final class CountingRelay extends OutboxRelay {
    private final AtomicInteger polls = new AtomicInteger();

    CountingRelay() {
      super(
          null,
          null,
          null,
          null,
          null,
          Clock.systemUTC(),
          1,
          1,
          RelayLeases.ownedBy("node-A", Duration.ofMinutes(1)));
    }

    @Override
    public void relay() {
      polls.incrementAndGet();
    }
  }

  @Test
  void theScheduledTriggerJustRunsAPoll() {
    CountingRelay relay = new CountingRelay();

    new OutboxRelayScheduler(relay).poll();

    assertEquals(1, relay.polls.get());
  }

  @Test
  void thePollCarriesNoScheduleLevelLock() throws NoSuchMethodException {
    assertFalse(
        Arrays.stream(OutboxRelayScheduler.class.getDeclaredMethod("poll").getAnnotations())
            .anyMatch(SchedulerLock.class::isInstance),
        "a lock on the schedule means the instance holding it is the only one polling, so losing "
            + "that instance stops delivery everywhere until the lock expires. The claim on each "
            + "row is what keeps concurrent pollers off each other now");
  }
}
