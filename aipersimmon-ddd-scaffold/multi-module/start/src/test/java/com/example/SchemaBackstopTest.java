package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.ordering.application.order.PlaceOrder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The invariants' last line of defence, asserted where it lives: in the schema.
 *
 * <p>The argument is V1_4's / V2_4's, applied to the rules it was not applied to: the aggregates'
 * guards and the tenant interceptor are the application, and everything that bypasses the
 * application — a data-fix script, an operator at a psql prompt, the raw {@code JdbcTemplate} these
 * tests themselves use — was running against no constraint at all. Every test here writes with the
 * raw template for exactly that reason: it is the bypass the constraints exist for. Before the
 * constraints landed, every insert below was accepted.
 *
 * <p>Shares its application context (and containers) with the other tests carrying this exact
 * {@code properties} block — a divergent copy would start its own container pair, and {@code
 * TestContextCountTest} pins the count.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class SchemaBackstopTest {

  private static final TenantId TENANT = Tenants.of("acme");

  @Autowired CommandBus commandBus;

  @Autowired JdbcTemplate jdbc;

  // --- inventory: the anti-oversell rule and the shape of a hold -------------------------------

  @Test
  void theDatabaseRefusesNegativeAvailableStock() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO inventory.stocks (sku, available, tenant_id) VALUES (?, ?, ?)",
                "SKU-BACKSTOP-NEG",
                -1,
                TENANT.value()),
        "available >= 0 is the anti-oversell rule itself; with no CHECK it was enforced only by"
            + " the application's optimistic lock, which a raw write never meets");
  }

  @Test
  void theDatabaseRefusesANonPositiveHeldQuantity() {
    jdbc.update(
        "INSERT INTO inventory.reservations (id, order_id, released, version, tenant_id)"
            + " VALUES ('res-backstop-1', 'order-backstop-r1', false, 1, ?)",
        TENANT.value());

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO inventory.reservation_lines (reservation_id, sku, quantity,"
                    + " tenant_id) VALUES ('res-backstop-1', 'SKU-1', 0, ?)",
                TENANT.value()),
        "a hold of zero or less is corrupt state the release path explodes on two transactions"
            + " later — the same rule Reservation's constructor enforces, mirrored where no"
            + " constructor runs");
  }

  // --- ordering: line shape, one-line-per-SKU, and the customer reference ----------------------

  @Test
  void theDatabaseRefusesANonPositiveOrDistortedOrderLine() {
    seedCustomer("cust-backstop-1");
    seedOrder("order-backstop-1", "cust-backstop-1");

    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertLine("order-backstop-1", 1, "SKU-1", 0, 100),
        "quantity must be > 0");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertLine("order-backstop-1", 1, "SKU-1", 1, -100),
        "unit_minor must be >= 0");
  }

  /**
   * {@code OrderHasDistinctSkus} runs in memory at placement and — correctly — is not re-run on
   * reconstitution. That leaves the stored line set with no guard at all: a bypassing write could
   * produce a duplicate-SKU row set whose {@code total()} and reservation semantics silently change
   * on the next load. The unique key is the invariant's mirror in the one layer that cannot be
   * bypassed.
   */
  @Test
  void theDatabaseRefusesASecondLineForTheSameSku() {
    seedCustomer("cust-backstop-2");
    seedOrder("order-backstop-2", "cust-backstop-2");
    insertLine("order-backstop-2", 1, "SKU-DUP", 1, 100);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertLine("order-backstop-2", 2, "SKU-DUP", 2, 100));
  }

  /**
   * Cross-context references stay FK-free on purpose (the schema boundary is the context boundary),
   * but customer and order share a context, a schema and a transaction — inside the boundary the
   * V1_4 argument applies with no counter-argument, and an order referencing a customer that does
   * not exist is exactly the dangling reference it catches.
   */
  @Test
  void theDatabaseRefusesAnOrderForAnUnknownCustomer() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> seedOrder("order-backstop-3", "cust-does-not-exist"));
  }

  // --- ordering: the audit timestamp ------------------------------------------------------------

  /**
   * Business time no longer lives only inside the UUIDv7 id (fine for cursors, unreadable for
   * audit, BI and support). The column is written from the application {@code Clock} — the same
   * source every other timestamp in the application uses — not a database default.
   */
  @Test
  void aPlacedOrderCarriesItsCreationTime() {
    seedCustomer("cust-backstop-4");
    seedStock("SKU-BACKSTOP-4");
    String orderId =
        TenantContext.runAs(
            TENANT,
            () ->
                commandBus.send(
                    new PlaceOrder(
                        "cust-backstop-4",
                        List.of(new PlaceOrder.Line("SKU-BACKSTOP-4", 1, 100, "USD")))));

    Instant createdAt =
        jdbc.queryForObject(
            "SELECT created_at FROM ordering.orders WHERE tenant_id = ? AND id = ?",
            Instant.class,
            TENANT.value(),
            orderId);

    assertNotNull(createdAt, "the order row must say when it was placed");
    assertTrue(
        Math.abs(ChronoUnit.MINUTES.between(createdAt, Instant.now())) < 5,
        "written from the application clock at placement, so it is now: " + createdAt);
  }

  // --- payment: the dedupe window's clock and the purge's cost ---------------------------------

  /**
   * {@code recorded_at} bounds the 30-day dedupe window, and a {@code TIMESTAMP} column silently
   * reinterprets its values under the session's time zone — a non-UTC session shifts the whole
   * window, and a shortened window is "a late redelivery authorizes a second time". {@code
   * timestamptz} makes the stored instant unambiguous.
   */
  @Test
  void paymentRecordedAtIsAnUnambiguousInstant() {
    assertEquals(
        "timestamp with time zone",
        jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns"
                + " WHERE table_schema = 'payment' AND table_name = 'payment_operations'"
                + " AND column_name = 'recorded_at'",
            String.class));
  }

  /**
   * The hourly purge deletes by {@code recorded_at <}, and without an index every run is a full
   * sequential scan whose cost grows with the retained history — measured on PostgreSQL 18.1 with
   * 1M retained rows: 8,345 buffers / ~86ms per run, no-op runs included. With the index the scan
   * touches only what expired: 18 buffers / 0.36ms typical, 3 buffers / 0.009ms when nothing has.
   * This pins the index so the measured property survives.
   */
  @Test
  void thePurgeHasAnIndexToDeleteBy() {
    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'payment'"
                    + " AND tablename = 'payment_operations'"
                    + " AND indexname = 'payment_operations_by_recorded_at'",
                Integer.class));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private void seedCustomer(String customerId) {
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES (?, 'Backstop', 1000000, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id) DO NOTHING",
        customerId,
        TENANT.value());
  }

  private void seedStock(String sku) {
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id) VALUES (?, 100, ?)"
            + " ON CONFLICT (tenant_id, sku) DO UPDATE SET available = EXCLUDED.available",
        sku,
        TENANT.value());
  }

  private void seedOrder(String orderId, String customerId) {
    jdbc.update(
        "INSERT INTO ordering.orders"
            + " (id, customer_id, status, total_minor, currency, version, tenant_id, created_at)"
            + " VALUES (?, ?, 'READY_FOR_FULFILMENT', 100, 'USD', 1, ?, now())",
        orderId,
        customerId,
        TENANT.value());
  }

  private void insertLine(String orderId, int lineNo, String sku, int quantity, long unitMinor) {
    jdbc.update(
        "INSERT INTO ordering.order_lines"
            + " (order_id, line_no, sku, quantity, unit_minor, currency, tenant_id)"
            + " VALUES (?, ?, ?, ?, ?, 'USD', ?)",
        orderId,
        lineNo,
        sku,
        quantity,
        unitMinor,
        TENANT.value());
  }
}
