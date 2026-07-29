package com.aipersimmon.ddd.outbox.engine.relay;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * The scheduled trigger for {@link OutboxRelay}, kept apart from the relay itself so the two can be
 * controlled separately.
 *
 * <p>Every instance runs this schedule, and that is deliberate. Mutual exclusion is per row: a poll
 * claims the rows it is going to dispatch and leases them, so instances polling at the same moment
 * take disjoint work. Guarding the schedule with a lock instead would put delivery behind a single
 * holder — and an instance killed while holding it releases nothing, so every other instance would
 * skip its poll, silently, until that lock expired. Leasing the rows makes a lost instance cost
 * only the rows it was holding, and only until their lease expires.
 *
 * <p>{@code @Scheduled(fixedDelay)} runs the task <em>first</em> and waits afterwards, so a large
 * {@code poll-delay-ms} does not stop a poll from happening at startup — it only delays the second
 * one. With the trigger on its own conditional bean, a deployment that relays from one dedicated
 * instance, or a test that drives delivery itself, turns the trigger off and calls {@link
 * OutboxRelay#relay()} directly. Such a call always runs: nothing can silently skip it.
 */
public class OutboxRelayScheduler {

  private final OutboxRelay relay;

  public OutboxRelayScheduler(OutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")
  public void poll() {
    relay.relay();
  }
}
