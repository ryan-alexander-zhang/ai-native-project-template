package com.example.payment.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.payment.domain.PaymentDecision;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Handles {@link VoidPayment}: settles, on the operation row, the race between ordering abandoning
 * an operation and this context authorizing it (issue-00144).
 *
 * <p>Three recorded shapes, three answers:
 *
 * <ul>
 *   <li><strong>nothing recorded</strong> — the authorization has not happened (and may never).
 *       Record {@code Voided} as the operation's outcome: a refusal in advance. An {@code
 *       AuthorizePayment} arriving later finds it and announces a decline instead of authorizing.
 *       If it races the authorization's own insert, the primary key resolves it exactly as it
 *       resolves two concurrent authorizations: the loser rolls back and its redelivery finds the
 *       winner's outcome.
 *   <li><strong>{@code Authorized}</strong> — the case the issue names: the hold exists and nobody
 *       will capture it. {@link PaymentOperations#markVoided} releases it (in a real deployment,
 *       alongside the provider's void call).
 *   <li><strong>{@code Declined} or {@code Voided}</strong> — nothing is held; a redelivered void
 *       or a void racing a decline falls through. Idempotent by construction, which is what lets
 *       ordering fire-and-forget this command over an at-least-once relay.
 * </ul>
 *
 * <p>No outcome event, unlike {@code AuthorizePaymentHandler}'s decide-once-announce-every-time:
 * that discipline exists because a flow waits on the announcement, and nothing waits on a void —
 * the ordering flow has already moved on when it asks.
 */
@Component
public class VoidPaymentHandler implements CommandHandler<VoidPayment, Void> {

  private final PaymentOperations operations;

  public VoidPaymentHandler(PaymentOperations operations) {
    this.operations = operations;
  }

  @Override
  public Void handle(VoidPayment command, CommandContext context) {
    Optional<PaymentDecision> recorded = operations.find(command.paymentOperationId());
    if (recorded.isEmpty()) {
      operations.record(command.paymentOperationId(), new PaymentDecision.Voided());
      return null;
    }
    if (recorded.get() instanceof PaymentDecision.Authorized) {
      operations.markVoided(command.paymentOperationId());
    }
    return null;
  }
}
