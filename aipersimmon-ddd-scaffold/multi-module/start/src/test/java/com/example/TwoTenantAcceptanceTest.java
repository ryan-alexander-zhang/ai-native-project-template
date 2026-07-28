package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves pool multi-tenancy (design-00009) end to end on the ordering scaffold: two tenants that
 * share the very same natural keys ({@code CUST-1} / {@code SKU-1}) are fully isolated. Each tenant
 * places an order under its own bound {@link TenantContext}; the write-side authority ({@code
 * CommandContext.tenantId}) rides the whole durable cascade — outbox → Kafka ({@code ce_tenantid})
 * → inbox → the process manager's {@code ConfirmOrder} — so each order confirms under, and only
 * under, its originating tenant.
 *
 * <p>The isolation assertions are the point: with a foreign tenant bound, the query bus cannot see
 * the other tenant's order at all (the MyBatis-Plus tenant-line interceptor scopes every read to
 * the ambient tenant), even though both orders exist for the same {@code CUST-1}. This is exactly
 * the §6 composite-key case — {@code CUST-1} and {@code SKU-1} legitimately repeat across tenants
 * because {@code tenant_id} joins their primary key.
 *
 * <p>Driven programmatically through {@link TenantContext#runAs} rather than the HTTP edge: a
 * relayed effect, a scheduler, or a message consumer binds the tenant the same way, and it keeps
 * the isolation proof independent of the web layer (which {@code ExceptionContractTest} covers).
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@Import(TestInfrastructure.class)
class TwoTenantAcceptanceTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);
  private static final TenantId ACME = Tenants.of("acme");
  private static final TenantId GLOBEX = Tenants.of("globex");

  @Autowired CommandBus commandBus;

  @Autowired QueryBus queryBus;

  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void seedBothTenants() {
    // Each tenant gets its own CUST-1 / SKU-1 — the same keys under different tenants, which the
    // composite primary keys (tenant_id, id) / (tenant_id, sku) now permit. Seeded with the raw
    // JdbcTemplate (which the tenant-line interceptor does not touch) so tenant_id is explicit.
    for (TenantId tenant : List.of(ACME, GLOBEX)) {
      jdbc.update(
          "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
              + " VALUES ('CUST-1', 'Acme', 1000000, 'USD', ?)"
              + " ON CONFLICT (tenant_id, id) DO NOTHING",
          tenant.value());
      jdbc.update(
          "INSERT INTO inventory.stocks (sku, available, tenant_id)"
              + " VALUES ('SKU-1', 1000, ?)"
              + " ON CONFLICT (tenant_id, sku) DO NOTHING",
          tenant.value());
    }
  }

  @Test
  void ordersAreConfirmedUnderTheirTenantAndInvisibleToTheOther() {
    String acmeOrder = placeAndConfirm(ACME);
    String globexOrder = placeAndConfirm(GLOBEX);

    // Isolation: neither tenant can see the other's order, though both are CUST-1 orders.
    assertTrue(
        TenantContext.runAs(GLOBEX, () -> queryBus.ask(new FindOrder(acmeOrder))).isEmpty(),
        "globex must not see acme's order");
    assertTrue(
        TenantContext.runAs(ACME, () -> queryBus.ask(new FindOrder(globexOrder))).isEmpty(),
        "acme must not see globex's order");

    // Each tenant does see its own.
    assertEquals(
        "CONFIRMED",
        TenantContext.runAs(ACME, () -> status(acmeOrder)),
        "acme sees its own confirmed order");
    assertEquals(
        "CONFIRMED",
        TenantContext.runAs(GLOBEX, () -> status(globexOrder)),
        "globex sees its own confirmed order");
  }

  @Test
  void theBoundTenantIsStampedOnTheAuditRow() {
    // The rewired OperationTenantResolver reads TenantContext — bound here to acme — so the audit
    // row carries the real tenant, not a constant. (Proof the tenant reaches the capture layer.)
    String order = TenantContext.runAs(ACME, () -> place());

    String tenant =
        jdbc.queryForObject(
            "SELECT tenant_id FROM aipersimmon_operation_log"
                + " WHERE operation_code = 'ordering.order.place' AND summary LIKE ?",
            String.class,
            "%" + order + "%");
    assertEquals("acme", tenant);
  }

  /**
   * The isolation above is enforced by the MyBatis-Plus tenant-line interceptor — that is, by the
   * application. This asserts the other half: the database refuses a cross-tenant reference on its
   * own, so a data-fix script, a migration, an operator at a psql prompt, or the raw {@link
   * JdbcTemplate} used right here cannot file one tenant's row under another tenant's parent.
   *
   * <p>Deliberately written with the raw template, which the interceptor never sees. Before {@code
   * V4} the foreign key was {@code order_lines.order_id} alone and this INSERT succeeded
   * (issue-00091): tenant isolation had exactly one enforcement point, and everything that went
   * around it had none.
   */
  @Test
  void anOrderLineCannotBeFiledUnderAnotherTenantsOrder() {
    String acmeOrder = TenantContext.runAs(ACME, () -> place());

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO ordering.order_lines"
                    + " (order_id, line_no, sku, quantity, unit_minor, currency, tenant_id)"
                    + " VALUES (?, 99, 'SKU-1', 1, 100, 'USD', ?)",
                acmeOrder,
                GLOBEX.value()),
        "the composite foreign key must refuse an acme order's line filed under globex");
  }

  /** Places a no-review order for CUST-1/SKU-1 and awaits the process manager confirming it. */
  private String placeAndConfirm(TenantId tenant) {
    String orderId = TenantContext.runAs(tenant, () -> place());
    await()
        .atMost(SETTLE)
        .untilAsserted(
            () -> assertEquals("CONFIRMED", TenantContext.runAs(tenant, () -> status(orderId))));
    return orderId;
  }

  /** Sends PlaceOrder on the current thread's bound tenant; returns the new order id. */
  private String place() {
    return commandBus.send(
        new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 1, 100, "USD"))));
  }

  /** Reads the order status under whatever tenant is currently bound. */
  private String status(String orderId) {
    return queryBus.ask(new FindOrder(orderId)).orElseThrow().status();
  }
}
