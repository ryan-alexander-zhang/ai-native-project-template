package com.example.payment.infrastructure;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.payment.application.PaymentOperations;
import com.example.payment.domain.PaymentDecision;
import java.time.Clock;
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
  private static final String VOIDED = "VOIDED";

  private final PaymentOperationMapper operations;

  /**
   * The application's Clock bean, not the database's CURRENT_TIMESTAMP (issue-00146). recorded_at
   * bounds the dedupe window and the cleanup expires by it with this same clock — one time source
   * for writing the window and for closing it, and a test can freeze it.
   */
  private final Clock clock;

  public MyBatisPaymentOperations(PaymentOperationMapper operations, Clock clock) {
    this.operations = operations;
    this.clock = clock;
  }

  @Override
  public Optional<PaymentDecision> find(String operationId) {
    PaymentOperationRow row = operations.find(tenant(), operationId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        switch (row.getOutcome()) {
          case AUTHORIZED -> new PaymentDecision.Authorized();
          case VOIDED -> new PaymentDecision.Voided();
          default -> new PaymentDecision.Declined(row.getDeclineCode(), row.getDeclineReason());
        });
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
        tenant(), operationId, outcomeOf(decision), declineCode, declineReason, clock.instant());
  }

  @Override
  public void markVoided(String operationId) {
    // The WHERE outcome = 'AUTHORIZED' guard makes this the no-op the port promises for every
    // other shape; zero rows updated is the expected answer for a redelivered void.
    operations.markVoided(tenant(), operationId);
  }

  private static String outcomeOf(PaymentDecision decision) {
    return switch (decision) {
      case PaymentDecision.Authorized ignored -> AUTHORIZED;
      case PaymentDecision.Declined ignored -> DECLINED;
      case PaymentDecision.Voided ignored -> VOIDED;
    };
  }

  /**
   * The tenant this row belongs to.
   *
   * <p>{@code effective()}, not {@code current().orElse(ROOT)}. What to do when nothing is bound is
   * a deployment-wide decision that {@code TenantContext} already makes from the tenancy mode — the
   * sentinel at N=1, a refusal when multi-tenancy is on. Deciding it again here means this one
   * write silently lands in the shared bucket on a thread that lost its binding, while every other
   * tenant-scoped write in the application refuses.
   */
  private static String tenant() {
    return TenantContext.effective().value();
  }
}
