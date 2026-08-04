package com.example.samples.s07;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s07.payments.application.PaymentReconciliation;
import com.example.samples.s07.payments.application.ReconciliationReport;
import com.example.thirdparty.paygate.GatewayMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The channel the catalogue insists on: what happens when the callback never comes.
 *
 * <p>Every test here begins the same way — a payment is requested, the relay sends it, and the provider
 * says nothing. That is the situation no amount of retrying fixes, because the answer exists on their side
 * and nothing is going to push it to us.
 *
 * <p>The round is driven by hand, and the tenant is bound the way the scheduler binds it, because a timer
 * thread inherits neither a request nor a tenant.
 */
class ReconciliationTest extends GatewayIntegrationTestBase {

  @Autowired private PaymentReconciliation reconciliation;

  @Test
  void apaymentWhoseCallbackNeverCameIsSettledByAsking() {
    gateway.mode(GatewayMode.SILENT);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();

    ReconciliationReport report = reconcile();

    // The money did move; only the news was lost. This is why "no callback" must never be read as "no
    // charge", and why the pull channel is not optional.
    assertThat(report.scanned()).isEqualTo(1);
    assertThat(report.settled()).isEqualTo(1);
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(gatewayRefOf(paymentId)).startsWith("gw_");
    assertThat(reviewReasonOf(paymentId)).isNull();
  }

  @Test
  void apaymentTheProviderIsStillDecidingIsMovedForwardNotEscalated() {
    gateway.mode(GatewayMode.SILENT_PENDING);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();

    ReconciliationReport report = reconcile();

    // "Accepted, no decision yet" is news too — it is the difference between a request that never arrived
    // and one that is in flight, and it costs a payment nothing to record.
    assertThat(report.settled()).isEqualTo(1);
    assertThat(report.escalated()).isEmpty();
    assertThat(statusOf(paymentId)).isEqualTo("SUBMITTED");
  }

  @Test
  void apaymentStillPendingPastTheDeadlineIsEscalatedAndNeverFailed() {
    gateway.mode(GatewayMode.SILENT_PENDING);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();
    backdate(paymentId);

    ReconciliationReport report = reconcile();

    assertThat(report.escalated()).hasSize(1);
    assertThat(reviewReasonOf(paymentId)).contains("still not decided");
    // The status is untouched. Timing out our patience says nothing about whether the customer was
    // charged, and a service that failed the payment here would refuse orders it had been paid for.
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
  }

  @Test
  void apaymentTheProviderHasNoRecordOfIsLeftAloneWhileItIsStillYoung() {
    // The charge request has not been sent at all — the relay is not run in this test — so of course the
    // provider has never heard of it. This is the test that caught the first version of the reconciler,
    // which escalated it: with both schedules on, the reconciler's timer beat the relay's by thirteen
    // milliseconds, and because an escalation is sticky the payment was then excluded from the scan
    // forever. Wrongly raising a review item is worse than raising it late.
    gateway.mode(GatewayMode.FORGET_CHARGE);
    String paymentId = requestPayment(someOrderRef(), 4500);

    ReconciliationReport report = reconcile();

    assertThat(report.awaiting()).isEqualTo(1);
    assertThat(report.escalated()).isEmpty();
    assertThat(reviewReasonOf(paymentId)).isNull();
  }

  @Test
  void apaymentTheProviderHasStillNeverHeardOfIsEscalated() {
    gateway.mode(GatewayMode.FORGET_CHARGE);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();
    backdate(paymentId);

    ReconciliationReport report = reconcile();

    // 404 past the deadline is its own answer: either the request never arrived or they lost it, and from
    // here those are indistinguishable. Both need a person, and neither is a failed payment.
    assertThat(report.escalated()).hasSize(1);
    assertThat(reviewReasonOf(paymentId)).contains("no record");
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
  }

  @Test
  void aresultCodeThePullChannelCannotInterpretIsEscalated() {
    // No callback URL at the provider — a misconfiguration that happens, and one which leaves the pull
    // channel as the only road the answer can travel. The answer, when it comes, is a code we do not know.
    gateway.callbacksTo(null);
    gateway.mode(GatewayMode.UNKNOWN_RESULT_CODE);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();
    // The provider decides a moment after accepting, so wait for it to have decided. Reconciling before
    // that would ask about a charge that is still legitimately pending and prove nothing.
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(20))
        .untilAsserted(() -> assertThat(gateway.resultCodeOf(paymentId)).contains("77"));

    ReconciliationReport report = reconcile();

    assertThat(report.escalated()).hasSize(1);
    assertThat(reviewReasonOf(paymentId)).contains("77");
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
  }

  @Test
  void aproviderThatCannotBeAskedLeavesThePaymentAlone() {
    gateway.callbacksTo(null);
    gateway.mode(GatewayMode.STATUS_QUERY_FAILS);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();

    ReconciliationReport report = reconcile();

    // "I could not ask" is a fact about now, not about the payment. A reconciler that mistook it for "there
    // is nothing there" would escalate the whole backlog the first time the provider had a bad minute.
    assertThat(report.unreachable()).isEqualTo(1);
    assertThat(report.escalated()).isEmpty();
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
    assertThat(reviewReasonOf(paymentId)).isNull();
  }

  @Test
  void anescalatedPaymentIsNotEscalatedAgainOnEveryRound() {
    gateway.mode(GatewayMode.FORGET_CHARGE);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();
    backdate(paymentId);
    reconcile();
    String firstReason = reviewReasonOf(paymentId);

    ReconciliationReport second = reconcile();

    // The candidate scan excludes what has already been flagged. Without that clause the same alert fires
    // every minute forever, and an alert that repeats forever is an alert nobody reads.
    assertThat(second.scanned()).isZero();
    assertThat(second.escalated()).isEmpty();
    assertThat(reviewReasonOf(paymentId)).isEqualTo(firstReason);
  }

  @Test
  void acallbackThatArrivesAfterTheEscalationStillSettlesThePayment() {
    gateway.mode(GatewayMode.FORGET_CHARGE);
    String paymentId = requestPayment(someOrderRef(), 4500);
    relay.relay();
    backdate(paymentId);
    reconcile();
    assertThat(reviewReasonOf(paymentId)).isNotNull();

    // The provider found it after all, three hours later. Because "we do not know" was modelled as a flag
    // and not as a status, the payment can still be settled — and a review item that resolved itself is
    // visible as one, instead of being a terminal state somebody has to unpick by hand.
    assertThat(notify(notificationBody(paymentId, "gw_late", "00")).getStatusCode().value())
        .isEqualTo(200);

    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(gatewayRefOf(paymentId)).isEqualTo("gw_late");
    assertThat(reviewReasonOf(paymentId)).as("the reason is history, not a verdict").isNotNull();
  }

  private ReconciliationReport reconcile() {
    return TenantContext.runAs(Tenants.ROOT, reconciliation::reconcileOnce);
  }

  /** Makes a payment look like it has been waiting an hour, without waiting an hour. */
  private void backdate(String paymentId) {
    jdbc.update(
        "UPDATE s07_payment SET requested_at = requested_at - INTERVAL '1 hour' WHERE id = ?",
        paymentId);
  }
}
