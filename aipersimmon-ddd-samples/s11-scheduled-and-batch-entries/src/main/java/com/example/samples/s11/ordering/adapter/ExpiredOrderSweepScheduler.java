package com.example.samples.s11.ordering.adapter;

import com.example.samples.s11.ordering.application.ExpiredOrderSweep;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The trigger, and only the trigger. An entry adapter like a controller: it converts something
 * arriving — here the passage of time — into a call on the application, and contains no logic of its
 * own worth testing.
 *
 * <p><strong>Every instance runs this schedule, and that is deliberate.</strong> The temptation is to
 * put a distributed lock around it so "only one instance sweeps". The library's own relay scheduler
 * explains why that trade is usually the wrong way round: guarding the schedule with a lock "would put
 * delivery behind a single holder — and an instance killed while holding it releases nothing, so every
 * other instance would skip its poll, silently, until that lock expired".
 *
 * <p>What makes running everywhere safe here is that the <em>work</em> is mutually exclusive, one row
 * at a time: closing an order is a version-checked state transition, so if two instances pick the same
 * order, one wins and the other is refused and counts a skip. No lock, no lease, no claim table — the
 * aggregate already had what was needed (S8). {@code SweepTest} asserts this by running a competing
 * sweep in the middle of one.
 *
 * <p>That reasoning has a boundary worth stating: it holds because the unit of work is a state change
 * on a row that carries a version. Work with nothing to version — "send a reminder email", "call a
 * partner API" — has nothing to arbitrate two instances, and then the answer is to claim the work
 * before doing it, with a lease that expires. The library's outbox relay is the reference for that
 * shape: {@code OutboxLease} carries an owner, a per-claim token and an expiry, so "an instance that
 * is killed mid-poll cannot release anything, so the rows it held become claimable again on their own".
 *
 * <p>{@code fixedDelay} runs the task first and waits afterwards, so a long delay does not stop a
 * sweep at startup — it only delays the second one.
 */
@Component
@ConditionalOnProperty(name = "ordering.sweep.enabled", havingValue = "true")
class ExpiredOrderSweepScheduler {

  private final ExpiredOrderSweep sweep;

  ExpiredOrderSweepScheduler(ExpiredOrderSweep sweep) {
    this.sweep = sweep;
  }

  /**
   * A timer thread inherits no tenant, so the trigger binds one before calling in. This deployment is
   * single-tenant, so it names the sentinel explicitly rather than leaving the question open; a
   * multi-tenant one would loop its tenants here, one bound round each, because "sweep every tenant
   * at once" is not something a tenant-scoped read can express.
   */
  @Scheduled(fixedDelayString = "${ordering.sweep.poll-delay-ms:1000}")
  void poll() {
    TenantContext.runAs(Tenants.ROOT, sweep::sweepOnce);
  }
}
