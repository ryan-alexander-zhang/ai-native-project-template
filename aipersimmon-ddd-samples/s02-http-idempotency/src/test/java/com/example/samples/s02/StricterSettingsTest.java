package com.example.samples.s02;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Two settings the default profile cannot use, proven in one place on purpose.
 *
 * <p>Each distinct property set is a distinct Spring context, and each context starts its own
 * PostgreSQL and Redis — so a class per setting costs a container cycle per setting. These two share
 * one because neither interferes with the other.
 *
 * <p>Why they cannot be in the default profile:
 *
 * <ul>
 *   <li>{@code require-key=true} covers every POST in the application, including the third-party
 *       callback, and no payment provider sends an Idempotency-Key. Idempotency has no
 *       url-patterns setting, so this really is all-or-nothing.
 *   <li>a limit of 3 would make every other test class flaky, and the real 100/min would need 101
 *       requests to demonstrate.
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.web.idempotency.require-key=true",
      "aipersimmon.ddd.web.rate-limit.limit=3",
      "aipersimmon.ddd.web.rate-limit.window=60s",
      // Bucketed by header rather than by IP, so the burst below cannot eat the other test's quota:
      // every request from these tests shares one source address.
      "aipersimmon.ddd.web.rate-limit.key=header"
    })
class StricterSettingsTest extends EdgeProtectionTestBase {

  @Test
  void withRequireKeyOnAKeylessWriteIsRefusedRatherThanRunUnprotected() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        http.exchange(
            "/orders",
            HttpMethod.POST,
            new HttpEntity<>("{\"clientReference\":\"ref-strict\",\"amountCents\":10}", headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
        .isEqualTo("/problems/idempotency-key-required");
    assertThat(JsonPath.<String>read(response.getBody(), "$.detail"))
        .isEqualTo("Missing Idempotency-Key header");
    assertThat(orderCount()).isZero();
  }

  @Test
  void theQuotaIsAdvertisedOnEveryAnswerAndEnforcedOnTheFourth() {
    ResponseEntity<String> first = read("burst-1");

    // A read of a missing order: 404 from the application, quota headers from the filter.
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(first.getHeaders().getFirst("RateLimit-Policy")).contains("q=3", "w=60");
    assertThat(first.getHeaders().getFirst("RateLimit")).contains("r=2");
    // headers=both, so the legacy triple is emitted alongside the IETF pair.
    assertThat(first.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("3");
    assertThat(first.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("2");

    read("burst-1");
    read("burst-1");

    // The counter increments before the allow test, so the fourth call in the window is the first
    // refused one.
    ResponseEntity<String> fourth = read("burst-1");

    assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(JsonPath.<String>read(fourth.getBody(), "$.type"))
        .isEqualTo("/problems/rate-limited");
    assertThat(JsonPath.<String>read(fourth.getBody(), "$.detail")).isEqualTo("Rate limit exceeded");
    // Never zero, so a client that honours it always waits.
    assertThat(Integer.parseInt(fourth.getHeaders().getFirst("Retry-After")))
        .isGreaterThanOrEqualTo(1);
    assertThat(fourth.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

    // A different api key is a different bucket, so it still has its full quota.
    assertThat(read("burst-2").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private ResponseEntity<String> read(String apiKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Api-Key", apiKey);
    return http.exchange(
        "/orders/none", HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
