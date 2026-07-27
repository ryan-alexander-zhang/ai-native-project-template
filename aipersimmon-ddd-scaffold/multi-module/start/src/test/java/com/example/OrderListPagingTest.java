package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
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
 * {@code GET /orders?customerId=…} — the read side answering from the tables, paged by cursor.
 *
 * <p>Three things this exercises that the single-order read cannot:
 *
 * <ul>
 *   <li><strong>A read model that is not the aggregate.</strong> Each row is one SQL join with the
 *       line totals summed; no order is rehydrated. {@code FindOrderHandler} may load the aggregate
 *       because it returns one order shaped like the aggregate — a list of fifty would be fifty
 *       rehydrations for data a read never changes.
 *   <li><strong>Cursor paging.</strong> The cursor is the last id of the page, because UUIDv7 ids
 *       sort by creation time. The assertions below therefore also pin that ids really are
 *       time-ordered: if they were random, the pages would interleave and the "no repeats, no gaps"
 *       check would fail.
 *   <li><strong>Tenant scoping on reads.</strong> The list statement writes no tenant predicate;
 *       the tenant-line interceptor adds it. A second tenant's orders must not appear, and that is
 *       asserted rather than assumed.
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class OrderListPagingTest {

  private static final String TENANT = "acme";
  private static final String OTHER_TENANT = "globex";

  private final TestRestTemplate http;
  private final JdbcTemplate jdbc;

  OrderListPagingTest(@Autowired TestRestTemplate http, @Autowired JdbcTemplate jdbc) {
    this.http = http;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void seedStock() {
    for (String tenant : new String[] {TENANT, OTHER_TENANT}) {
      jdbc.update(
          "INSERT INTO inventory.stocks (sku, available, tenant_id)"
              + " VALUES ('SKU-1', 1000, ?)"
              + " ON CONFLICT (tenant_id, sku) DO NOTHING",
          tenant);
    }
  }

  /**
   * Each test gets its own customer. The list is scoped by customer, so sharing one would make
   * every assertion about "the whole list" depend on which other tests had run first.
   */
  private String customer(String tenant, String id) {
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES (?, 'Acme', 10000000, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id) DO NOTHING",
        id,
        tenant);
    return id;
  }

  @Test
  void thePagesCoverEveryOrderExactlyOnceNewestFirst() {
    String customer = customer(TENANT, "CUST-PAGES");
    List<String> placed = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      placed.add(placeOrder(TENANT, customer, 2));
    }

    List<String> walked = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page = list(TENANT, customer, cursor, 2);
      page.path("items").forEach(item -> walked.add(item.path("id").asText()));
      cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
      pages++;
    } while (cursor != null && pages < 10);

    // Newest first: the reverse of the order they were placed in. This holds only because the ids
    // are time-ordered — the query sorts by id alone.
    List<String> newestFirst = new ArrayList<>(placed);
    java.util.Collections.reverse(newestFirst);
    assertEquals(newestFirst, walked, "every order exactly once, newest first, across three pages");
    assertEquals(3, pages, "5 orders at 2 per page is 3 pages");
  }

  @Test
  void theLastPageSaysSoAndAnEmptyResultIsNotAnError() {
    String customer = customer(TENANT, "CUST-LAST-PAGE");
    placeOrder(TENANT, customer, 1);

    JsonNode onlyPage = list(TENANT, customer, null, 20);
    assertEquals(1, onlyPage.path("items").size());
    assertTrue(onlyPage.path("nextCursor").isNull(), "a final page carries no cursor");

    JsonNode nobody = list(TENANT, "CUST-UNKNOWN", null, 20);
    assertEquals(0, nobody.path("items").size(), "a customer with no orders is an empty page");
    assertTrue(nobody.path("nextCursor").isNull());
  }

  @Test
  void aLineTotalIsSummedBySqlNotByLoadingTheAggregate() {
    String customer = customer(TENANT, "CUST-TOTALS");
    String id = placeOrder(TENANT, customer, 3);

    JsonNode item = itemFor(list(TENANT, customer, null, 20), id);
    assertNotNull(item, "the placed order must appear in its customer's list");
    // 3 x 100 minor units, summed in the statement that read the row.
    assertEquals(300, item.path("totalMinor").asLong());
    assertEquals("USD", item.path("currency").asText());
    assertEquals("FULFILMENT_IN_PROGRESS", item.path("status").asText());
  }

  @Test
  void anotherTenantsOrdersAreNotListed() {
    String customer = customer(TENANT, "CUST-SHARED-ID");
    customer(OTHER_TENANT, "CUST-SHARED-ID");
    String mine = placeOrder(TENANT, customer, 1);
    String theirs = placeOrder(OTHER_TENANT, customer, 1);

    JsonNode myList = list(TENANT, customer, null, 50);
    assertNotNull(itemFor(myList, mine));
    assertNull(
        itemFor(myList, theirs),
        "the list statement writes no tenant predicate — the interceptor must add it");
  }

  private String placeOrder(String tenant, String customerId, int quantity) {
    String body =
        """
        {"customerId":"%s",
         "lines":[{"sku":"SKU-1","quantity":%d,"unitAmountMinor":100,"currency":"USD"}]}
        """
            .formatted(customerId, quantity);
    HttpHeaders headers = tenantHeader(tenant);
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Void> placed =
        http.postForEntity("/orders", new HttpEntity<>(body, headers), Void.class);
    assertEquals(201, placed.getStatusCode().value());
    String location = placed.getHeaders().getLocation().getPath();
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private JsonNode list(String tenant, String customerId, String cursor, int size) {
    String url = "/orders?customerId=" + customerId + "&size=" + size;
    if (cursor != null) {
      url += "&cursor=" + cursor;
    }
    ResponseEntity<JsonNode> response =
        http.exchange(url, HttpMethod.GET, new HttpEntity<>(tenantHeader(tenant)), JsonNode.class);
    assertEquals(200, response.getStatusCode().value());
    return response.getBody();
  }

  private static JsonNode itemFor(JsonNode page, String orderId) {
    for (JsonNode item : page.path("items")) {
      if (orderId.equals(item.path("id").asText())) {
        return item;
      }
    }
    return null;
  }

  private static HttpHeaders tenantHeader(String tenant) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", tenant);
    return headers;
  }

  @Test
  void aPageNeverRepeatsAnOrderFromTheOneBefore() {
    String customer = customer(TENANT, "CUST-CURSOR");
    for (int i = 0; i < 4; i++) {
      placeOrder(TENANT, customer, 1);
    }

    JsonNode first = list(TENANT, customer, null, 2);
    String cursor = first.path("nextCursor").asText();
    JsonNode second = list(TENANT, customer, cursor, 2);

    List<String> firstIds = ids(first);
    List<String> secondIds = ids(second);
    assertFalse(
        secondIds.stream().anyMatch(firstIds::contains),
        "an offset would drift under concurrent inserts; a cursor must not");
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.path("items").forEach(item -> ids.add(item.path("id").asText()));
    return ids;
  }
}
