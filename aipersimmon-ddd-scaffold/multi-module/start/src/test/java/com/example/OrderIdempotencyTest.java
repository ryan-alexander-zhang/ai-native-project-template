package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.store.jdbc.JdbcIdempotencyStore;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Placing an order is a create, so a client that never saw the response must be able to retry
 * without buying twice. {@code aipersimmon.ddd.web.idempotency} makes that safe: the first request
 * under a key runs and its response is stored; a repeat of that key replays the stored response
 * instead of reaching the controller at all.
 *
 * <p>What the retry must <em>not</em> do is the point of the assertions: no second order row, and
 * no second {@code OrderReadyForFulfilment} in the outbox. Storing the response is only half of it
 * — an idempotent endpoint that still emitted the integration event twice would have inventory
 * reserve stock twice for one order.
 *
 * <h2>The key is per tenant</h2>
 *
 * <p>The filter is ordered after tenant resolution and the store's primary key is {@code
 * (tenant_id, idempotency_key)}, so two tenants sending the same key each get their own order. That
 * is asserted below, because the alternative — a global key space — would let one tenant's retry
 * suppress another tenant's genuine request.
 *
 * <h2>A rough edge worth knowing (issue-00064)</h2>
 *
 * <p>The stored response keeps the status, the body and {@code Content-Type} — and nothing else. A
 * replayed {@code 201} therefore arrives <strong>without its {@code Location} header</strong>, so a
 * client that retried cannot learn where its order lives. This test pins that behaviour rather than
 * hiding it: it is the current contract, and the sample should not pretend otherwise.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class OrderIdempotencyTest {

  private static final String TENANT = "acme";
  private static final String OTHER_TENANT = "globex";

  private final TestRestTemplate http;
  private final JdbcTemplate jdbc;
  private final IdempotencyStore idempotencyStore;

  OrderIdempotencyTest(
      @Autowired TestRestTemplate http,
      @Autowired JdbcTemplate jdbc,
      @Autowired IdempotencyStore idempotencyStore) {
    this.http = http;
    this.jdbc = jdbc;
    this.idempotencyStore = idempotencyStore;
  }

  @BeforeEach
  void seedTenants() {
    // The Flyway seed lives under __root__; each tenant needs its own customer and stock.
    for (String tenant : new String[] {TENANT, OTHER_TENANT}) {
      jdbc.update(
          "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
              + " VALUES ('CUST-1', 'Acme', 100000, 'USD', ?)"
              + " ON CONFLICT (tenant_id, id) DO NOTHING",
          tenant);
      jdbc.update(
          "INSERT INTO inventory.stocks (sku, available, tenant_id)"
              + " VALUES ('SKU-1', 10, ?)"
              + " ON CONFLICT (tenant_id, sku) DO NOTHING",
          tenant);
    }
  }

  @Test
  void theSharedStoreIsTheOneInUse() {
    // Precondition, and the regression guard for issue-00062: the web starter also declares an
    // in-memory IdempotencyStore under @ConditionalOnMissingBean, so without an explicit ordering
    // edge the two race and the per-JVM map can win — while allow-in-memory-stores=false would then
    // refuse to start. This module is the only place both configurations coexist, so this is where
    // the ordering can be asserted at all.
    assertInstanceOf(JdbcIdempotencyStore.class, idempotencyStore);
  }

  @Test
  void retryingTheSameKeyPlacesOneOrderAndEmitsOneEvent() {
    long ordersBefore = orderCount(TENANT);
    long outboxBefore = outboxRows();

    ResponseEntity<String> first = placeOrder(TENANT, "key-retry-1");
    ResponseEntity<String> retry = placeOrder(TENANT, "key-retry-1");

    assertEquals(201, first.getStatusCode().value());
    assertEquals(201, retry.getStatusCode().value(), "the retry replays the stored response");
    assertEquals(ordersBefore + 1, orderCount(TENANT), "a retry must not place a second order");
    assertEquals(
        outboxBefore + 1,
        outboxRows(),
        "a retry must not ask inventory to reserve the stock a second time");

    assertNotNull(first.getHeaders().getLocation(), "the original response carries Location");
    // Pinning issue-00064, not endorsing it: the store keeps status, body and Content-Type only,
    // so the replay loses Location. A client that retried has no way to find its order.
    assertNull(
        retry.getHeaders().getLocation(),
        "if this starts passing Location through, issue-00064 was fixed — update this assertion");
  }

  @Test
  void aDifferentKeyIsADifferentOrder() {
    long ordersBefore = orderCount(TENANT);

    URI first = placeOrder(TENANT, "key-distinct-a").getHeaders().getLocation();
    URI second = placeOrder(TENANT, "key-distinct-b").getHeaders().getLocation();

    assertNotEquals(first, second, "two keys are two intents");
    assertEquals(ordersBefore + 2, orderCount(TENANT));
  }

  @Test
  void twoTenantsMayUseTheSameKey() {
    long acmeBefore = orderCount(TENANT);
    long globexBefore = orderCount(OTHER_TENANT);

    placeOrder(TENANT, "key-shared");
    placeOrder(OTHER_TENANT, "key-shared");

    assertEquals(acmeBefore + 1, orderCount(TENANT));
    assertEquals(
        globexBefore + 1,
        orderCount(OTHER_TENANT),
        "a second tenant's request must not be mistaken for the first tenant's retry");
  }

  private ResponseEntity<String> placeOrder(String tenant, String idempotencyKey) {
    String body =
        """
        {"customerId":"CUST-1",
         "lines":[{"sku":"SKU-1","quantity":1,"unitAmountMinor":100,"currency":"USD"}]}
        """;
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Tenant-Id", tenant);
    headers.set("Idempotency-Key", idempotencyKey);
    return http.postForEntity("/orders", new HttpEntity<>(body, headers), String.class);
  }

  private long orderCount(String tenant) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from ordering.orders where tenant_id = ?", Long.class, tenant);
    return count == null ? 0 : count;
  }

  private long outboxRows() {
    Long count = jdbc.queryForObject("select count(*) from aipersimmon_outbox", Long.class);
    return count == null ? 0 : count;
  }
}
