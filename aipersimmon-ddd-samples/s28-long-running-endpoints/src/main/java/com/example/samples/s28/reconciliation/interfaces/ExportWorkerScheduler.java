package com.example.samples.s28.reconciliation.interfaces;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s28.reconciliation.application.ExportWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The trigger, and only the trigger — an entry adapter like a controller, converting the passage of time into a call
 * on the application.
 *
 * <p>{@code fixedDelay} rather than {@code fixedRate}: a run that takes longer than the delay must not have the next
 * one start on top of it. With a rate, a four-minute export on a one-second schedule would queue up two hundred
 * overlapping polls, each of which would claim a different job — which sounds like throughput and is actually one
 * thread pool's worth of exports competing for the same connection pool.
 *
 * <p>One thread, therefore one export at a time per instance. That is a deliberately conservative default: the
 * concurrency of this queue is the number of instances, which is a number somebody deploys rather than a number
 * buried in a scheduler annotation.
 */
@Component
@ConditionalOnProperty(name = "s28.worker.enabled", havingValue = "true")
class ExportWorkerScheduler {

  private final ExportWorker worker;

  ExportWorkerScheduler(ExportWorker worker) {
    this.worker = worker;
  }

  /** A timer thread inherits no tenant, so the trigger binds the sentinel before calling in — as S11 does. */
  @Scheduled(fixedDelayString = "${s28.worker.poll-delay:500ms}")
  void poll() {
    TenantContext.runAs(
        Tenants.ROOT,
        () -> {
          worker.runOne();
        });
  }
}
