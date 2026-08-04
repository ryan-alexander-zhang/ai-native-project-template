package com.example.samples.s07;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The inbound half: who is allowed to speak, and what the aggregate does with what they say.
 *
 * <p>The notifications are hand-posted rather than triggered from the provider, because each test needs
 * exactly one thing to be wrong — a body that does not match its signature, a nonce that has been seen, an
 * outcome that arrives after a later one. {@code ProviderDrivenFlowTest} does the same paths end to end,
 * with the stub actually calling back.
 *
 * <p>The signing is done with the provider's own {@code CallbackSigner}, so a mismatch between the two
 * halves of the scheme would fail here rather than being papered over by a test that reimplements the
 * verifier's idea of the canonical form.
 */
class CallbackIngestionTest extends GatewayIntegrationTestBase {

  @Test
  void asignedNotificationSettlesThePayment() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    assertThat(notify(notificationBody(paymentId, "gw_1", "PND")).getBody()).contains("APPLIED");
    assertThat(statusOf(paymentId)).isEqualTo("SUBMITTED");

    assertThat(notify(notificationBody(paymentId, "gw_1", "00")).getBody()).contains("APPLIED");
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    // The provider's reference, kept because a refund or a support conversation needs it and it exists
    // nowhere else in our systems.
    assertThat(gatewayRefOf(paymentId)).isEqualTo("gw_1");
  }

  @Test
  void anunsignedNotificationIsRefused() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    ResponseEntity<String> response =
        notifyWithSignature(
            notificationBody(paymentId, "gw_1", "00"),
            Instant.now().getEpochSecond(),
            "nonce-" + UUID.randomUUID(),
            null);

    // A callback endpoint is an unauthenticated POST from the public internet. Anyone who knows the URL
    // and a payment id could otherwise settle payments at will.
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
  }

  @Test
  void abodyThatDoesNotMatchItsSignatureIsRefused() {
    String paymentId = requestPayment(someOrderRef(), 4500);
    long timestamp = Instant.now().getEpochSecond();
    String nonce = "nonce-" + UUID.randomUUID();
    String signed = notificationBody(paymentId, "gw_1", "51");

    // Signed a decline, sent a success. This is the attack the signature is actually for: not forging a
    // message from nothing, but editing one that was intercepted.
    ResponseEntity<String> response =
        notifyWithSignature(
            notificationBody(paymentId, "gw_1", "00"),
            timestamp,
            nonce,
            com.example.thirdparty.paygate.CallbackSigner.sign(timestamp, nonce, signed, SECRET));

    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
  }

  @Test
  void asignatureMadeWithTheWrongSecretIsRefused() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    ResponseEntity<String> response =
        notify(
            notificationBody(paymentId, "gw_1", "00"),
            Instant.now().getEpochSecond(),
            "nonce-" + UUID.randomUUID(),
            "not-the-shared-secret");

    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
  }

  @Test
  void aperfectlySignedButStaleNotificationIsRefused() {
    String paymentId = requestPayment(someOrderRef(), 4500);
    long sixMinutesAgo = Instant.now().minusSeconds(360).getEpochSecond();

    ResponseEntity<String> response =
        notify(
            notificationBody(paymentId, "gw_1", "00"),
            sixMinutesAgo,
            "nonce-" + UUID.randomUUID(),
            SECRET);

    // The tolerance window is what bounds how long a captured request stays useful. Note the timestamp is
    // covered by the signature, so an attacker cannot simply refresh it.
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
  }

  @Test
  void thesameSignedBytesCannotBeSentTwice() {
    String paymentId = requestPayment(someOrderRef(), 4500);
    String body = notificationBody(paymentId, "gw_1", "00");
    long timestamp = Instant.now().getEpochSecond();
    String nonce = "nonce-" + UUID.randomUUID();

    assertThat(notify(body, timestamp, nonce, SECRET).getStatusCode().value()).isEqualTo(200);

    // Byte-identical, and refused — by the nonce, not by the signature, which is still perfectly valid.
    // This is the tier of defence a signature alone does not provide, and it is the reason this sample
    // needs the web-store schema: the nonce has to be remembered across instances and restarts.
    assertThat(notify(body, timestamp, nonce, SECRET).getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void aduplicateNotificationWithAFreshSignatureIsAcceptedAndChangesNothing() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    notify(notificationBody(paymentId, "gw_1", "00"));
    long versionAfterFirst = versionOf(paymentId);

    // Same outcome, new event id, new nonce, new signature. Every byte differs, so the replay guard is
    // right to let it through — deduplicating this is the aggregate's job and no filter can do it.
    ResponseEntity<String> second = notify(notificationBody(paymentId, "gw_1", "00"));

    assertThat(second.getStatusCode().value()).isEqualTo(200);
    assertThat(second.getBody()).contains("DUPLICATE");
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(reviewReasonOf(paymentId)).isNull();
    // The write happened (the handler saves unconditionally) but nothing about the payment changed.
    assertThat(versionOf(paymentId)).isGreaterThan(versionAfterFirst);
  }

  @Test
  void anacceptanceArrivingAfterASuccessDoesNotUndoIt() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    notify(notificationBody(paymentId, "gw_1", "00"));
    ResponseEntity<String> late = notify(notificationBody(paymentId, "gw_1", "PND"));

    // Nothing about a webhook delivery is ordered, so the aggregate compares states rather than trusting
    // arrival order or the sender's clock.
    assertThat(late.getStatusCode().value()).isEqualTo(200);
    assertThat(late.getBody()).contains("SUPERSEDED");
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
  }

  @Test
  void contradictoryTerminalNotificationsKeepTheFirstAndAskForAHuman() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    notify(notificationBody(paymentId, "gw_1", "00"));
    ResponseEntity<String> contradiction = notify(notificationBody(paymentId, "gw_1", "51"));

    // Answered 200 on purpose: the delivery is fine, so asking for it again achieves nothing. The first
    // answer stands and a person is told, because no rule chooses correctly between "charged" and
    // "refused" — one choice ships goods for free and the other refuses a paying customer.
    assertThat(contradiction.getStatusCode().value()).isEqualTo(200);
    assertThat(contradiction.getBody()).contains("CONTRADICTED");
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(reviewReasonOf(paymentId)).contains("FAILED").contains("SUCCEEDED");
  }

  @Test
  void aresultCodeThisServiceCannotInterpretIsFlaggedRatherThanTreatedAsAFailure() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    ResponseEntity<String> response = notify(notificationBody(paymentId, "gw_1", "77"));

    // The most expensive default an integration like this can have is "unknown means failed": the day the
    // provider introduces a code for a successful charge under a new scheme, every one of them is recorded
    // as a failure and the customers who were charged are the ones who complain.
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).contains("ESCALATED");
    assertThat(statusOf(paymentId)).isEqualTo("REQUESTED");
    assertThat(reviewReasonOf(paymentId)).contains("77");
  }

  @Test
  void anotificationNamingAPaymentThisServiceDoesNotHaveIs404() {
    ResponseEntity<String> response =
        notify(notificationBody("00000000-0000-0000-0000-000000000000", "gw_1", "00"));

    // Not 200. It means the callback URL is shared with another environment, or the notification is for
    // somebody else's transaction — both configuration mistakes a person has to see. And there is no race
    // that could cause it: the charge request only leaves this service after the payment row has
    // committed, so the provider cannot know an id we have not stored.
    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  private long versionOf(String paymentId) {
    return single("SELECT version FROM s07_payment WHERE id = ?", Long.class, paymentId);
  }
}
