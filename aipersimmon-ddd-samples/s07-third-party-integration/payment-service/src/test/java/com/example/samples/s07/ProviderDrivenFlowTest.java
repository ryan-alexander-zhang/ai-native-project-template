package com.example.samples.s07;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.thirdparty.paygate.GatewayMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The same paths as {@code CallbackIngestionTest}, but with the provider driving: a real charge request
 * over a socket, a real signed callback coming back the other way, on its own threads and in its own time.
 *
 * <p>Worth having both. The hand-posted tests can make exactly one thing wrong; these prove that the two
 * halves of the signing scheme actually agree, that the callback URL is reachable, and that the ordering
 * defences hold when the ordering is decided by a scheduler rather than by the test.
 */
class ProviderDrivenFlowTest extends GatewayIntegrationTestBase {

  @Test
  void theprovidernotifiesAcceptanceThenSuccessAndThePaymentEndsSucceeded() {
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    awaitCallbacks(2);
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(gatewayRefOf(paymentId)).startsWith("gw_");
    // Every callback was answered 2xx. A provider that gets anything else redelivers, so a 4xx here would
    // turn a handled notification into an unbounded stream of them.
    assertThat(gateway.callbackResponses()).containsOnly(200);
  }

  @Test
  void thesameOutcomeNotifiedTwiceLeavesOnePaymentAndOneCharge() {
    gateway.mode(GatewayMode.DUPLICATE_CALLBACK);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    awaitCallbacks(2);
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(gateway.callbackResponses()).containsOnly(200);
    assertThat(gateway.chargesCreated()).isEqualTo(1);
    assertThat(reviewReasonOf(paymentId)).as("a duplicate is not an anomaly").isNull();
  }

  @Test
  void notificationsThatArriveInTheWrongOrderStillLeaveThePaymentSucceeded() {
    gateway.mode(GatewayMode.REVERSED_CALLBACKS);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    // Waiting for both deliveries rather than for the status: waiting for SUCCEEDED would pass before the
    // late acceptance arrived, which is precisely the thing under test.
    awaitCallbacks(2);
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(gateway.callbackResponses()).containsOnly(200);
  }

  @Test
  void adeclineIsRecordedAsAFailedPaymentAndNotAsAnError() {
    gateway.mode(GatewayMode.DECLINE);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    awaitCallbacks(2);
    assertThat(statusOf(paymentId)).isEqualTo("FAILED");
    // No review flag: the provider answered, we understood, and the answer was no. A refusal is an outcome
    // of the business, not a fault of the integration, and treating it as one buries the real faults.
    assertThat(reviewReasonOf(paymentId)).isNull();
  }

  @Test
  void twoContradictoryTerminalNotificationsEndInAReviewRatherThanAGuess() {
    gateway.mode(GatewayMode.CONTRADICTORY_CALLBACKS);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    awaitCallbacks(2);
    assertThat(statusOf(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(reviewReasonOf(paymentId)).isNotNull();
    assertThat(gateway.callbackResponses()).containsOnly(200);
  }

  @Test
  void aresultCodeAddedAfterWeWereWrittenEndsInAReview() {
    gateway.mode(GatewayMode.UNKNOWN_RESULT_CODE);
    String paymentId = requestPayment(someOrderRef(), 4500);

    relay.relay();

    awaitCallbacks(2);
    assertThat(reviewReasonOf(paymentId)).contains("77");
    // Accepted, not refused: redelivery would produce the same unknown code, so we take responsibility for
    // it locally instead of asking to be told again.
    assertThat(gateway.callbackResponses()).containsOnly(200);
    assertThat(statusOf(paymentId)).isEqualTo("SUBMITTED");
  }

  /** Waits for the provider to have delivered — and been answered — this many notifications. */
  private void awaitCallbacks(int count) {
    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> assertThat(gateway.callbackResponses()).hasSize(count));
  }
}
