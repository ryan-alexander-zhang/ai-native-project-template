package com.aipersimmon.ddd.outbox.mybatisplus;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The scheduled trigger for {@link OutboxRelay}, kept apart from the relay itself so the two can be
 * controlled separately.
 *
 * <p>That separation is the point. {@code @Scheduled(fixedDelay)} runs the task <em>first</em> and
 * waits afterwards, so a large {@code poll-delay-ms} does not stop a poll from happening at startup
 * — it only delays the second one. And because {@code @SchedulerLock} silently skips a method whose
 * lock is held, a caller invoking the relay directly while a scheduled poll holds the lock does
 * nothing at all, without error. With the trigger on its own conditional bean, anything that must
 * drive the relay itself — an integration test, or a deployment that relays from one dedicated
 * instance — turns the trigger off and calls {@link OutboxRelay#relay()} with no lock in the way.
 *
 * <p>The lock stays here rather than on the relay because it guards the <em>schedule</em>: it is
 * what keeps many instances from polling the same rows at once. A direct call is a deliberate act
 * by one caller and needs no such guard.
 */
public class OutboxRelayScheduler {

  private final OutboxRelay relay;

  public OutboxRelayScheduler(OutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")
  @SchedulerLock(
      name =
          "${aipersimmon.ddd.outbox.relay.lock-name:${spring.application.name:aipersimmon}-outbox-relay}",
      lockAtMostFor = "${aipersimmon.ddd.outbox.relay.lock-at-most-for:PT60M}")
  public void poll() {
    relay.relay();
  }
}
