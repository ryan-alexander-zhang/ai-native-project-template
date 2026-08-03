package com.example.samples.s02;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The signing client lives here, and it has to mirror the verifier bean exactly: the library defines
 * no canonical form, so "what is signed" is the application's contract, not the framework's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookReplayProtectionTest extends EdgeProtectionTestBase {

  private static final String SECRET = "s02-shared-secret";
  private static final String BODY = "{\"paymentId\":\"pay-1\",\"status\":\"CAPTURED\"}";

  @Test
  void aProperlySignedFreshCallbackIsAccepted() {
    ResponseEntity<String> response = send(BODY, Instant.now(), "nonce-1", true);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<String>read(response.getBody(), "$.accepted")).isEqualTo("pay-1");
  }

  @Test
  void anUnsignedCallbackIsRejected() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response = post(BODY, headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
        .isEqualTo("/problems/replay-rejected");
    // Every rejection shares one problem type; the detail is what tells them apart.
    assertThat(JsonPath.<String>read(response.getBody(), "$.detail"))
        .isEqualTo("Missing signature or timestamp");
  }

  @Test
  void aStaleTimestampIsRejectedEvenThoughTheSignatureIsValid() {
    ResponseEntity<String> response =
        send(BODY, Instant.now().minusSeconds(6 * 60), "nonce-2", true);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JsonPath.<String>read(response.getBody(), "$.detail"))
        .isEqualTo("Request timestamp outside tolerance");
  }

  @Test
  void aTamperedBodyIsRejected() {
    HttpHeaders headers = signed(BODY, Instant.now(), "nonce-3");

    // Signature computed over the original body, sent with a different one.
    ResponseEntity<String> response = post(BODY.replace("CAPTURED", "REFUNDED"), headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isEqualTo("Invalid signature");
  }

  @Test
  void theSameSignedBytesCannotBeSentTwice() {
    Instant timestamp = Instant.now();
    HttpHeaders headers = signed(BODY, timestamp, "nonce-4");

    assertThat(post(BODY, headers).getStatusCode()).isEqualTo(HttpStatus.OK);
    // Identical, still inside the tolerance window, and still perfectly signed — which is exactly
    // what a signature alone cannot stop. The nonce is what does, and it is off by default.
    ResponseEntity<String> replay = post(BODY, headers);

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JsonPath.<String>read(replay.getBody(), "$.detail")).isEqualTo("Replayed request");
  }

  @Test
  void aMissingNonceIsRejectedWhenTheNonceTierIsOn() {
    ResponseEntity<String> response = send(BODY, Instant.now(), "nonce-5", false);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isEqualTo("Missing nonce");
  }

  @Test
  void theOrdersEndpointIsNotSignedBecauseTheFilterOnlyCoversWebhooks() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "unsigned-1");

    ResponseEntity<String> response =
        http.exchange(
            "/orders",
            HttpMethod.POST,
            new HttpEntity<>("{\"clientReference\":\"ref-unsigned\",\"amountCents\":10}", headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private ResponseEntity<String> send(
      String body, Instant timestamp, String nonce, boolean sendNonce) {
    HttpHeaders headers = signed(body, timestamp, nonce);
    if (!sendNonce) {
      headers.remove("X-Nonce");
    }
    return post(body, headers);
  }

  /** The client half of the contract: {@code <epochSeconds>.<nonce>.<body>} under HMAC-SHA256, hex. */
  private HttpHeaders signed(String body, Instant timestamp, String nonce) {
    long epochSeconds = timestamp.getEpochSecond();
    String canonical = epochSeconds + "." + nonce + "." + body;
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Signature", hmacHex(canonical));
    // Epoch seconds as a decimal long: the filter parses nothing else, not ISO-8601.
    headers.set("X-Timestamp", Long.toString(epochSeconds));
    headers.set("X-Nonce", nonce);
    return headers;
  }

  private ResponseEntity<String> post(String body, HttpHeaders headers) {
    return http.exchange(
        "/webhooks/payment", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private static String hmacHex(String canonical) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
