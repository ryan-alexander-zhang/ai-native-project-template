package com.example.samples.s07;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.example.samples.s07.LocalNotes.NoteRecorded;
import com.example.thirdparty.paygate.GatewayMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbound half: what is written, when it leaves, what the provider receives, and what happens when
 * it refuses.
 *
 * <p>The relay's schedule is off, so each test drives one poll and asserts on exactly what that poll did.
 */
class OutboundIntentTest extends GatewayIntegrationTestBase {

  private static final String CHARGE_REQUESTED = "com.example.samples.payments.ChargeRequested";

  @Autowired private IntegrationEvents integrationEvents;
  @Autowired private TransactionTemplate transactions;
  @Autowired private LocalNotes.Recorder localNotes;

  @Test
  void thepaymentRowAndTheOutboxRowCommitTogether() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    // The two failure modes this removes are the ones nobody can repair afterwards: a payment we promised
    // to make and forgot, and a charge with no local record of why.
    assertThat(countOf("s07_payment")).isEqualTo(1);
    assertThat(countOf("aipersimmon_outbox")).isEqualTo(1);
    assertThat(single("SELECT type FROM aipersimmon_outbox", String.class))
        .isEqualTo(CHARGE_REQUESTED);
    assertThat(single("SELECT subject FROM aipersimmon_outbox", String.class)).isEqualTo(paymentId);
  }

  @Test
  void theoutboxRowRemembersWhereItWasGoing() {
    requestPayment(someOrderRef(), 4500);

    // Resolved in the writing transaction and stored, not re-decided at dispatch time. The library's own
    // argument for that: a row whose route was re-read from the annotations of whatever code is deployed
    // later could fall through to in-process delivery and be marked sent.
    assertThat(single("SELECT destination FROM aipersimmon_outbox", String.class))
        .isEqualTo("gateway:charges");
  }

  @Test
  void nothingReachesTheProviderBeforeTheRelayRuns() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    // The handler made no call. This is the claim that keeps a database connection out of a network wait
    // and keeps an unrollbackable side effect out of a transaction.
    assertThat(gateway.chargeRequests()).isZero();
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");

    relay.relay();

    assertThat(gateway.chargeRequests()).isEqualTo(1);
    assertThat(gateway.chargesCreated()).isEqualTo(1);
  }

  @Test
  void theproviderIsSentThePaymentIdAsItsIdempotencyKey() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    // The key existed before the first attempt and cannot change between attempts, which is the entire
    // safety argument of an at-least-once outbound channel that moves money.
    assertThat(gateway.idempotencyKeys()).containsExactly(paymentId);
  }

  @Test
  void atransientFailureLeavesTheRowUnsentAndTheNextPollRetriesIt() {
    gateway.mode(GatewayMode.FAIL_FIRST_CHARGE);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();
    assertThat(gateway.chargeRequests()).isEqualTo(1);
    assertThat(gateway.chargesCreated()).as("this 503 came before any charge existed").isZero();
    assertThat(countOf("aipersimmon_dead_letter")).as("a 503 is not hopeless").isZero();

    relay.relay();

    assertThat(gateway.chargeRequests()).as("the row was retried").isEqualTo(2);
    assertThat(gateway.chargesCreated()).isEqualTo(1);
    // Note what this test does NOT prove: nothing had been charged when the error arrived, so a retry with
    // a fresh key would also have charged exactly once. The next test is the one the key is for.
    assertThat(gateway.idempotencyKeys()).containsExactly(paymentId, paymentId);
  }

  @Test
  void achargeWhoseResponseWasLostIsRetriedAndTheCustomerIsDebitedOnce() {
    // The failure that makes the key load-bearing: the provider charged, and then the answer never got
    // back to us. "We got an error, so nothing happened" is not a safe reading of a remote call.
    gateway.mode(GatewayMode.LOSE_FIRST_RESPONSE);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();
    assertThat(gateway.chargesCreated()).as("the money moved on the first attempt").isEqualTo(1);

    relay.relay();

    assertThat(gateway.chargeRequests()).isEqualTo(2);
    // Two requests, one charge. Replace the header with a per-attempt value and this is 2 — checked, not
    // assumed: with a fresh UUID per attempt the assertion below fails with two distinct keys and two
    // charges.
    assertThat(gateway.chargesCreated()).isEqualTo(1);
    assertThat(gateway.idempotencyKeys()).containsExactly(paymentId, paymentId);
  }

  @Test
  void arefusedRequestIsDeadLetteredOnItsFirstAttemptRatherThanRetriedTenTimes() {
    gateway.mode(GatewayMode.REFUSE_CHARGE_REQUEST);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    // The library's default classifier would call a 400 transient and spend ten attempts and an hour of
    // backoff on a request the provider will refuse every time. GatewayFailureClassifier is why it does
    // not — and the value is the timing: an operator sees this now.
    assertThat(countOf("aipersimmon_dead_letter")).isEqualTo(1);
    assertThat(gateway.chargeRequests()).isEqualTo(1);

    relay.relay();
    assertThat(gateway.chargeRequests()).as("a dead letter is not retried").isEqualTo(1);

    // And the payment tells the truth about itself: nothing was charged, and nothing pretends otherwise.
    // What stops this being a silent loss is the reconciler, which will find it unsettled and ask.
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
    assertThat(reviewReasonOf(paymentId)).isNull();
  }

  @Test
  void alocalEventStillReachesItsListenerAfterTheDispatcherWasReplaced() {
    localNotes.clear();
    String paymentId = "note-" + UUID.randomUUID();

    // Published the same way any handler would, inside a transaction, through the same port. The only
    // difference from a charge request is that its class carries no @Externalized.
    transactions.executeWithoutResult(
        status ->
            integrationEvents.publish(
                new NoteRecorded(paymentId, "the leg is wired"),
                CommandContext.root(TenantContext.effective(), UUID.randomUUID().toString())));

    assertThat(
            single(
                "SELECT destination FROM aipersimmon_outbox WHERE subject = ?",
                String.class,
                paymentId))
        .as("a LOCAL event has no destination")
        .isNull();

    relay.relay();

    // Without the in-process leg this row would have been dispatched by a transport that does not
    // recognise it, returned normally, and been marked sent. No exception, no dead letter, nothing to
    // notice — which is why the leg is composed back in and why this assertion exists.
    assertThat(localNotes.notesFor(paymentId)).hasSize(1);
    assertThat(localNotes.notesFor(paymentId).get(0).payload().note()).isEqualTo("the leg is wired");
  }
}
