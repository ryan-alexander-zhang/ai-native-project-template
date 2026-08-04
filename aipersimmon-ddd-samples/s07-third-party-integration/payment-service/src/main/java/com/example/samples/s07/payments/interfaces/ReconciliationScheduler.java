package com.example.samples.s07.payments.interfaces;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s07.payments.application.PaymentReconciliation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The trigger, and only the trigger — an entry adapter for the passage of time, exactly as S11 argues it.
 *
 * <p>Separate from the round it starts so that a test, an operator endpoint and this timer all drive the
 * same method, and so that a deployment can reconcile from one dedicated instance by turning the property
 * off everywhere else. The library splits its own outbox relay from its relay scheduler for the same
 * reason.
 *
 * <p>A timer thread inherits no tenant, so the trigger binds one before calling in. This deployment is
 * single-tenant and names the sentinel explicitly rather than leaving the question to be answered by
 * whatever happens to be bound; a multi-tenant one would loop its tenants here, one bound round each.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: a round that takes longer than the interval must not have
 * a second one launched on top of it. The provider is a shared, rate-limited resource, and overlapping
 * rounds would ask it the same questions twice.
 */
@Component
@ConditionalOnProperty(name = "payments.reconciliation.enabled", havingValue = "true")
class ReconciliationScheduler {

  private final PaymentReconciliation reconciliation;

  ReconciliationScheduler(PaymentReconciliation reconciliation) {
    this.reconciliation = reconciliation;
  }

  @Scheduled(fixedDelayString = "${payments.reconciliation.poll-delay-ms:60000}")
  void poll() {
    TenantContext.runAs(Tenants.ROOT, reconciliation::reconcileOnce);
  }
}
