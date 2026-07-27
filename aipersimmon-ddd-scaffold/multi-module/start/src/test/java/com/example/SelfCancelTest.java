package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A customer cancelling their own order — and, before that, being told whether they can.
 *
 * <p>The pair is the point. {@code cancellableByCustomer} on the order snapshot is the
 * specification answering, so a client can offer or hide the action; {@code POST
 * /orders/{id}/cancel} is the aggregate deciding, and refusing with a coded reason. The same
 * statement of the window backs both, so the advice and the outcome cannot drift.
 *
 * <p>Deliberately not asserted: that the handler pre-checks eligibility. It does not, and should
 * not — a check outside the aggregate runs against a snapshot a concurrent write can invalidate
 * before the save. Advance notice and authorisation are different jobs.
 *
 * <p>The properties match {@code OrderIdempotencyTest} and {@code OrderListPagingTest} exactly, so
 * all three share one application context and one pair of containers.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class SelfCancelTest {

  private static final String TENANT = "acme";

  private final TestRestTemplate http;
  private final JdbcTemplate jdbc;

  SelfCancelTest(@Autowired TestRestTemplate http, @Autowired JdbcTemplate jdbc) {
    this.http = http;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void seed() {
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES ('CUST-CANCEL', 'Acme', 100000, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id) DO NOTHING",
        TENANT);
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id)"
            + " VALUES ('SKU-RESTRICTED', 100, ?)"
            + " ON CONFLICT (tenant_id, sku) DO NOTHING",
        TENANT);
  }

  @Test
  void anOrderHeldForReviewSaysItIsCancellableAndThenIsCancelled() {
    // SKU-RESTRICTED is on the review watchlist, so the order waits in AWAITING_REVIEW — inside the
    // customer's window.
    String order = placeHeldOrder();

    assertTrue(
        snapshot(order).path("cancellableByCustomer").asBoolean(),
        "the specification must say so before the client offers the action");

    assertEquals(204, cancel(order, "CUST-CANCEL").getStatusCode().value());
    assertEquals("CANCELLED", snapshot(order).path("status").asText());
    assertFalse(
        snapshot(order).path("cancellableByCustomer").asBoolean(),
        "and stops saying so once there is nothing left to cancel");
  }

  @Test
  void someoneElseAskingIsRefusedWithTheReason() {
    String order = placeHeldOrder();

    ResponseEntity<JsonNode> refused = cancel(order, "CUST-SOMEONE-ELSE");

    // FORBIDDEN category: not your order is an authorisation answer, not a rule violation.
    assertEquals(403, refused.getStatusCode().value());
    assertEquals(
        "ordering.not-order-customer",
        refused.getBody().path("code").asText(),
        "the refusal names which rule said no — that is what a specification's boolean cannot do");
    assertEquals("AWAITING_REVIEW", snapshot(order).path("status").asText());
  }

  private String placeHeldOrder() {
    String body =
        """
        {"customerId":"CUST-CANCEL",
         "lines":[{"sku":"SKU-RESTRICTED","quantity":1,"unitAmountMinor":100,"currency":"USD"}]}
        """;
    HttpHeaders headers = tenantHeader();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Void> placed =
        http.postForEntity("/orders", new HttpEntity<>(body, headers), Void.class);
    assertEquals(201, placed.getStatusCode().value());
    return placed.getHeaders().getLocation().getPath();
  }

  private ResponseEntity<JsonNode> cancel(String order, String customerId) {
    return http.exchange(
        order + "/cancel?customerId=" + customerId,
        HttpMethod.POST,
        new HttpEntity<>(tenantHeader()),
        JsonNode.class);
  }

  private JsonNode snapshot(String order) {
    return http.exchange(order, HttpMethod.GET, new HttpEntity<>(tenantHeader()), JsonNode.class)
        .getBody();
  }

  private static HttpHeaders tenantHeader() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", TENANT);
    return headers;
  }
}
