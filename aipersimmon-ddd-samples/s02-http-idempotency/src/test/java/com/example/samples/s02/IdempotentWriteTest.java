package com.example.samples.s02;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** What an {@code Idempotency-Key} does, what it does not do, and where the business key takes over. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotentWriteTest extends EdgeProtectionTestBase {

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM s02_order");
  }

  @Test
  void aRetryWithTheSameKeyBuysOnceAndReplaysTheFirstAnswer() {
    ResponseEntity<String> first = place("key-1", "ref-1", 1000);
    ResponseEntity<String> second = place("key-1", "ref-1", 1000);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    // Byte-for-byte the first response, including the id that was minted once.
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(orderCount()).isEqualTo(1);
  }

  @Test
  void onlyTheAllowListedHeadersComeBackOnAReplay() {
    String location = place("key-2", "ref-2", 1000).getHeaders().getFirst(HttpHeaders.LOCATION);

    ResponseEntity<String> replay = place("key-2", "ref-2", 1000);

    // Content-Type, Location, ETag and Content-Language are replayed; nothing else is stored.
    assertThat(replay.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(location);
    assertThat(replay.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_JSON);
  }

  @Test
  void anOverlongKeyIsRefusedBeforeTheHandlerRuns() {
    ResponseEntity<String> response = place("k".repeat(256), "ref-4", 1000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
        .isEqualTo("/problems/idempotency-key-too-long");
    assertThat(orderCount()).isZero();
  }

  @Test
  void reusingAKeyForAMeasurablyDifferentRequestIsRefused() {
    place("key-5", "ref-5", 1000);

    // A different content length is part of the fingerprint, so this is caught.
    ResponseEntity<String> response = place("key-5", "ref-5", 1000000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
        .isEqualTo("/problems/idempotency-key-reused");
    assertThat(orderCount()).isEqualTo(1);
  }

  @Test
  void aDifferentBodyOfTheSameShapeIsNOTDetected() {
    ResponseEntity<String> first = place("key-6", "ref-6", 1000);

    // Same method, path, query, content type and content length — so the same fingerprint. The
    // library hashes those five things and NOT the body, so this second, genuinely different request
    // is served the first one's response and never reaches the handler. Scope keys per operation and
    // do not rely on the fingerprint to catch a changed payload.
    ResponseEntity<String> second = place("key-6", "ref-7", 2000);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(JsonPath.<String>read(second.getBody(), "$.clientReference")).isEqualTo("ref-6");
    assertThat(orderCount()).isEqualTo(1);
  }

  @Test
  void aClientErrorIsADecidedOutcomeAndIsReplayedToo() {
    ResponseEntity<String> first = place("key-8", "", 1000);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<String> second = place("key-8", "", 1000);

    // 4xx is stored: the request was decided, and repeating it must not decide differently. Only 5xx
    // releases the claim so the next attempt can execute.
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(second.getBody()).isEqualTo(first.getBody());
  }

  @Test
  void adifferentKeyForTheSameBusinessOrderIsTheUniqueIndexsJob() {
    assertThat(place("key-9", "ref-9", 1000).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // A fresh key, so the edge store has never seen this submission and the handler does run. The
    // UNIQUE index refuses the insert, the interceptor chain translates DuplicateKeyException into
    // DuplicateEntityException, and the web layer renders 409. This is why the two mechanisms are not
    // substitutes for one another.
    ResponseEntity<String> response = place("key-10", "ref-9", 1000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(orderCount()).isEqualTo(1);
  }

  private ResponseEntity<String> place(String key, String clientReference, long amountCents) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      headers.set("Idempotency-Key", key);
    }
    String body =
        "{\"clientReference\":\"%s\",\"amountCents\":%d}".formatted(clientReference, amountCents);
    return http.exchange(
        "/orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }
}
