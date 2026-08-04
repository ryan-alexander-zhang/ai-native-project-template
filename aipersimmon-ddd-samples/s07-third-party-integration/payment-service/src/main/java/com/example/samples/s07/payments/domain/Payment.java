package com.example.samples.s07.payments.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;

/**
 * One attempt to take money through a system we do not control.
 *
 * <p>Everything the sample says about unreliable integration is enforced here, in code that makes no
 * HTTP call and knows no result codes. Three rules:
 *
 * <ol>
 *   <li><strong>Forward only.</strong> A notification that says less than what we already know is
 *       ignored ({@link SettlementOutcome#SUPERSEDED}), which is what makes out-of-order delivery a
 *       non-event.
 *   <li><strong>Repetition is free.</strong> The same terminal answer twice changes nothing
 *       ({@link SettlementOutcome#DUPLICATE}). Every channel here is at-least-once — the outbound
 *       relay, the callback, the reconciler — so idempotence is not a nicety, it is the precondition
 *       for any of them being safe.
 *   <li><strong>Contradiction escalates, never resolves.</strong> Two different terminal answers keep
 *       the first and raise a flag. The aggregate has no basis to choose, and code that guesses here
 *       is code that occasionally ships goods for free.
 * </ol>
 *
 * <p>Not knowing is modelled as {@link #reviewReason()} rather than as a status, so a late callback can
 * still settle a payment a human has been asked to look at. An escalation is a request for attention,
 * not a verdict.
 */
@AggregateRoot
public final class Payment extends AbstractAggregateRoot<PaymentId> {

  private final PaymentId id;
  private final String orderRef;
  private final long amountMinor;
  private final Instant requestedAt;

  private PaymentStatus status;
  private String gatewayRef;
  private String reviewReason;

  private Payment(
      PaymentId id,
      String orderRef,
      long amountMinor,
      Instant requestedAt,
      PaymentStatus status,
      String gatewayRef,
      String reviewReason) {
    this.id = id;
    this.orderRef = orderRef;
    this.amountMinor = amountMinor;
    this.requestedAt = requestedAt;
    this.status = status;
    this.gatewayRef = gatewayRef;
    this.reviewReason = reviewReason;
  }

  /**
   * Record the intent. Nothing has been sent, and the row that says so is what makes the intent
   * survive a crash between "the customer clicked pay" and "the gateway heard about it".
   */
  public static Payment request(PaymentId id, String orderRef, long amountMinor, Instant now) {
    Payment payment =
        new Payment(id, orderRef, amountMinor, now, PaymentStatus.REQUESTED, null, null);
    payment.checkInvariant(new AmountIsPositive(amountMinor));
    return payment;
  }

  public static Payment reconstitute(
      PaymentId id,
      String orderRef,
      long amountMinor,
      Instant requestedAt,
      PaymentStatus status,
      String gatewayRef,
      String reviewReason,
      long version) {
    Payment payment =
        new Payment(id, orderRef, amountMinor, requestedAt, status, gatewayRef, reviewReason);
    payment.restoreVersion(version);
    return payment;
  }

  /**
   * Apply what the gateway said, from whichever channel said it.
   *
   * <p>One method for both the callback and the reconciler on purpose. Two sources of truth that each
   * decided how to update state would eventually disagree, and the disagreement would be
   * timing-dependent and unreproducible. Here the reconciler is not a second implementation of
   * settlement, it is a second courier for the same one.
   *
   * @param gatewayRef the provider's own reference, kept for support and refunds. Taken even from a
   *     superseded notification if we do not have it yet: a late {@code ACCEPTED} is stale about the
   *     status but still correct about the identity.
   */
  public SettlementOutcome recordGatewayResult(GatewayOutcome outcome, String gatewayRef) {
    if (this.gatewayRef == null && gatewayRef != null) {
      this.gatewayRef = gatewayRef;
    }
    PaymentStatus notified = outcome.status();

    if (status.isTerminal() && notified.isTerminal() && notified != status) {
      // Both cannot be true. Keep the first, ask for help, and answer the gateway 2xx so it stops
      // redelivering — the problem is not the delivery.
      flagForReview(
          "the gateway reported " + notified + " after " + status + "; both cannot be true");
      return SettlementOutcome.CONTRADICTED;
    }
    if (notified == status) {
      // Nothing changed, terminal or not. A repeated ACCEPTED is as uninteresting as a repeated
      // SUCCEEDED, and both are told apart from APPLIED so a caller can log the one and not the other.
      return SettlementOutcome.DUPLICATE;
    }
    if (status.supersedes(notified)) {
      return SettlementOutcome.SUPERSEDED;
    }
    status = notified;
    return SettlementOutcome.APPLIED;
  }

  /**
   * Ask a human to look at this payment, without pretending to know the answer.
   *
   * <p>Idempotent by first-writer-wins, which matters because the reconciler runs on a schedule: an
   * escalation that re-fired every tick would overwrite the original reason with a later, vaguer one
   * and turn one alert into an unbounded stream.
   */
  public void flagForReview(String reason) {
    if (reviewReason == null) {
      reviewReason = reason;
    }
  }

  @Override
  public PaymentId id() {
    return id;
  }

  public String orderRef() {
    return orderRef;
  }

  public long amountMinor() {
    return amountMinor;
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  public PaymentStatus status() {
    return status;
  }

  public String gatewayRef() {
    return gatewayRef;
  }

  public String reviewReason() {
    return reviewReason;
  }

  public boolean needsReview() {
    return reviewReason != null;
  }
}
