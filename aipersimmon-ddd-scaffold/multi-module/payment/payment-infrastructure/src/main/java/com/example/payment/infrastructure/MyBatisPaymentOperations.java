package com.example.payment.infrastructure;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.payment.application.PaymentOperations;
import com.example.payment.domain.PaymentDecision;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentOperations} over {@code payment_operations}, written through the command's
 * transaction.
 *
 * <p>This replaced a {@code ConcurrentHashMap}, and the reason is worth stating precisely, because
 * the map was not simply "not durable enough". A {@code putIfAbsent} cannot be rolled back: if the
 * transaction that claimed an operation then failed — the outbox insert, a later interceptor, a
 * dropped connection — the claim survived while the outcome event did not, and every subsequent
 * redelivery found the operation already handled and published nothing. The authorization was lost
 * silently and permanently.
 *
 * <p>Swapping in an ordinary table written in its own transaction would have left the hole exactly
 * as it was, which is the part worth remembering. What closes it is writing through the
 * <em>caller's</em> transaction — the mapper shares the command's {@code SqlSession}, so the claim
 * and the outcome event commit or roll back together.
 *
 * <p>Tenant-scoped, and part of the primary key: the operation id is derived from a message id in
 * the originating tenant's own causal chain, so two tenants may legitimately produce the same one.
 * Stamped explicitly here because {@code payment_operations} is deliberately absent from the
 * tenant-line interceptor's allow-list — it is framework-shaped plumbing rather than a
 * consumer-owned domain table.
 */
@Component
public class MyBatisPaymentOperations implements PaymentOperations {

  private static final String AUTHORIZED = "AUTHORIZED";
  private static final String DECLINED = "DECLINED";

  private final PaymentOperationMapper operations;

  public MyBatisPaymentOperations(PaymentOperationMapper operations) {
    this.operations = operations;
  }

  @Override
  public Optional<PaymentDecision> find(String operationId) {
    PaymentOperationRow row = operations.find(tenant(), operationId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        AUTHORIZED.equals(row.getOutcome())
            ? new PaymentDecision.Authorized()
            : new PaymentDecision.Declined(row.getDeclineCode(), row.getDeclineReason()));
  }

  @Override
  public void record(String operationId, PaymentDecision decision) {
    String declineCode = null;
    String declineReason = null;
    if (decision instanceof PaymentDecision.Declined declined) {
      declineCode = declined.code();
      declineReason = declined.reason();
    }
    operations.record(
        tenant(),
        operationId,
        decision.isAuthorized() ? AUTHORIZED : DECLINED,
        declineCode,
        declineReason);
  }

  /**
   * The bound tenant, or the {@code __root__} sentinel when nothing is bound — which is what the
   * command bus itself falls back to, so a bus-dispatched authorization lands under the same tenant
   * its order did.
   */
  private static String tenant() {
    return TenantContext.current().orElse(Tenants.ROOT).value();
  }
}
