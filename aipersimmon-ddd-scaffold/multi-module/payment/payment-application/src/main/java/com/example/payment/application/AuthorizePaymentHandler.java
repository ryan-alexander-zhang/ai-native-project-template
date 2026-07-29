package com.example.payment.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.payment.api.PaymentAuthorized;
import com.example.payment.api.PaymentDeclined;
import com.example.payment.domain.AuthorizationPolicy;
import com.example.payment.domain.PaymentDecision;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Handles {@link AuthorizePayment}: applies the domain {@link AuthorizationPolicy} and announces
 * the outcome — {@link PaymentAuthorized} or {@link PaymentDeclined}. Reporting the outcome as an
 * event (rather than a return value or a throw) is what lets the ordering process manager treat
 * authorisation and decline as the two branches of the fulfilment flow.
 *
 * <p>Authorising a payment is an irreversible action, so it is guarded by the {@code
 * paymentOperationId} business idempotency key rather than trusting transport-level dedupe alone
 * (design-00004 §13.2).
 *
 * <h2>Decide once, announce every time</h2>
 *
 * <p>The shape below is the point of this class, and it is not the obvious one. A redelivery does
 * <em>not</em> return silently: it looks up the decision already recorded and republishes it. The
 * authorization happens at most once — that is what the log is for — but the outcome event is
 * emitted on every delivery, because the whole premise of at-least-once delivery is that the
 * previous one may never have arrived. Returning silently would make the guarantee "exactly one
 * authorization and <em>at most</em> one outcome", which is a different and much weaker promise
 * than the one this flow is built on (issue-00069).
 *
 * <p>Republishing is safe because the reader is idempotent by construction: {@code
 * OrderFulfilmentDefinition} dispatches on {@code (step, input)}, so a second {@code
 * PaymentAuthorized} arriving after the flow has moved on is ignored rather than acted on.
 *
 * <p>Both halves run in the command's transaction, which is the property the pattern actually
 * depends on — see {@link PaymentOperations}. If the publish rolls back, so does the record, and
 * the redelivery genuinely re-authorises rather than finding a claim left behind by work that never
 * committed.
 */
@Component
public class AuthorizePaymentHandler implements CommandHandler<AuthorizePayment, Void> {

  /**
   * Injected, not instantiated. This was a {@code new AuthorizationPolicy()} field, which made the
   * one rule every real deployment must replace the one thing it could not replace without editing
   * this class.
   */
  private final AuthorizationPolicy authorization;

  private final IntegrationEvents integrationEvents;
  private final PaymentOperations operations;

  public AuthorizePaymentHandler(
      AuthorizationPolicy authorization,
      IntegrationEvents integrationEvents,
      PaymentOperations operations) {
    this.authorization = authorization;
    this.integrationEvents = integrationEvents;
    this.operations = operations;
  }

  @Override
  public Void handle(AuthorizePayment command, CommandContext context) {
    Optional<PaymentDecision> recorded = operations.find(command.paymentOperationId());

    // Decide only when this operation has never been decided. On a redelivery the recorded
    // decision is reused verbatim — re-running the policy could reach a different answer if a
    // rule or a rate changed in between, and an operation must not have two outcomes.
    PaymentDecision decision =
        recorded.orElseGet(() -> authorization.decide(command.amountMinor(), command.currency()));
    if (recorded.isEmpty()) {
      operations.record(command.paymentOperationId(), decision);
    }

    // One exit for both paths: first delivery and redelivery announce the same outcome the same
    // way. Nothing here distinguishes them, which is exactly the property that makes a lost
    // outcome event recoverable.
    switch (decision) {
      case PaymentDecision.Authorized ignored ->
          integrationEvents.publish(new PaymentAuthorized(command.orderId()), context);
      case PaymentDecision.Declined declined ->
          integrationEvents.publish(
              new PaymentDeclined(command.orderId(), declined.code(), declined.reason()), context);
    }
    return null;
  }
}
