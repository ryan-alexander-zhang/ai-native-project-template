package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.springframework.jdbc.core.ConnectionCallback;
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

  /**
   * The tests above prove the cursor is <em>correct</em>. This one proves it is <em>cheap</em>, and
   * nothing else here can: an unindexed cursor query returns exactly the same right pages, just by
   * reading the whole table to find them — which is the cost cursor paging exists to avoid. The two
   * properties come from different places (time-ordered ids; an index) and only one of them was
   * ever asserted, which is how a scaffold ends up teaching half a technique (issue-00073).
   */
  @Test
  void aPageIsAnsweredByAnIndexRangeScanNotAFullScan() {
    String customer = customer(TENANT, "CUST-PLAN");
    for (int i = 0; i < 3; i++) {
      placeOrder(TENANT, customer, 1);
    }
    jdbc.execute("ANALYZE ordering.orders");
    jdbc.execute("ANALYZE ordering.order_lines");

    String listPlan =
        plan(
            "SELECT o.id FROM ordering.orders o"
                + " WHERE o.tenant_id = ? AND o.customer_id = ? AND o.id < ?"
                + " ORDER BY o.id DESC LIMIT 21",
            TENANT,
            customer,
            "ffffffff-ffff-ffff-ffff-ffffffffffff");
    assertTrue(
        listPlan.contains("orders_by_customer_newest_first"),
        () ->
            "the page must be one range scan of the (tenant_id, customer_id, id DESC) index:\n"
                + listPlan);

    String linesPlan =
        plan(
            "SELECT line_no FROM ordering.order_lines WHERE tenant_id = ? AND order_id = ?",
            TENANT,
            "any-order-id");
    assertTrue(
        linesPlan.contains("order_lines_by_order"),
        () ->
            "the join/child-read side needs its own index — PostgreSQL does not index the child"
                + " side of a foreign key:\n"
                + linesPlan);
  }

  /**
   * {@code EXPLAIN} with sequential scans priced out of reach, so the plan shows which index path
   * <em>exists</em> rather than which one the planner happens to prefer on a table this small.
   *
   * <p>The caller asserts on the index's <em>name</em>, not on the absence of "Seq Scan", and that
   * is deliberate: with no index at all the planner does not fall back to a full scan here, it
   * walks the primary key and applies the two predicates as a Filter — same cost, no "Seq Scan" in
   * the plan. Naming the index is the only assertion that actually distinguishes the two.
   *
   * <p>Run through a single {@link Connection} because the SET is session state and {@link
   * JdbcTemplate} borrows a fresh connection per call; RESET in a finally so the pooled connection
   * goes back unchanged.
   */
  private String plan(String sql, Object... args) {
    return jdbc.execute(
        (ConnectionCallback<String>)
            connection -> {
              try (Statement session = connection.createStatement()) {
                session.execute("SET enable_seqscan = off");
              }
              try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + sql)) {
                for (int i = 0; i < args.length; i++) {
                  explain.setObject(i + 1, args[i]);
                }
                StringBuilder plan = new StringBuilder();
                try (ResultSet rows = explain.executeQuery()) {
                  while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                  }
                }
                return plan.toString();
              } finally {
                try (Statement session = connection.createStatement()) {
                  session.execute("RESET enable_seqscan");
                }
              }
            });
  }
}
