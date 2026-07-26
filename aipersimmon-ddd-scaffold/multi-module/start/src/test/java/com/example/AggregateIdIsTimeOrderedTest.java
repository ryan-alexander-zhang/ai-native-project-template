package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.ordering.application.order.PlaceOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A business aggregate's primary key is a time-ordered UUIDv7 from {@code IdGenerator}, not a
 * random {@code UUID.randomUUID()}.
 *
 * <p>Regression guard for issue-00054. The scaffold used to mint {@code OrderId} with {@code
 * UUID.randomUUID()} while {@code ordering.orders.id} is a {@code VARCHAR(64) PRIMARY KEY} — the
 * scattered-index-write pattern decision-00019 introduced {@code IdGenerator} to remove, on the
 * highest-volume table in the schema. Because the scaffold is what a new project copies, the wrong
 * pattern would have propagated by default.
 *
 * <p>The id stays opaque: this asserts only the UUID version and, for a batch minted in sequence,
 * that lexicographic order follows creation order — the property that makes the key insert near the
 * tail of the index. Nothing here treats the embedded timestamp as a readable contract.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=1h",
      "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
    })
@Import(TestInfrastructure.class)
class AggregateIdIsTimeOrderedTest {

  private static final TenantId TENANT = Tenants.of("acme");

  @Autowired CommandBus commandBus;

  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void seedTenant() {
    // The V1 seed rows belong to the __root__ sentinel, and (tenant_id, id) / (tenant_id, sku) are
    // composite keys, so this tenant needs its own CUST-1 / SKU-1. Seeded with the raw
    // JdbcTemplate,
    // which the tenant-line interceptor does not rewrite, so tenant_id is explicit.
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES ('CUST-1', 'Acme', 1000000, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id) DO NOTHING",
        TENANT.value());
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id)"
            + " VALUES ('SKU-1', 1000, ?)"
            + " ON CONFLICT (tenant_id, sku) DO NOTHING",
        TENANT.value());
  }

  @Test
  void anOrderIdIsAUuidv7() {
    String orderId = TenantContext.runAs(TENANT, this::place);

    assertEquals(
        7,
        UUID.fromString(orderId).version(),
        "the aggregate primary key must come from IdGenerator (UUIDv7), not UUID.randomUUID()");
  }

  @Test
  void orderIdsMintedInSequenceSortInCreationOrder() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(TenantContext.runAs(TENANT, this::place));
    }

    List<String> sorted = ids.stream().sorted().toList();
    assertEquals(ids, sorted, "a time-ordered key inserts at the tail of the index, not at random");
    assertTrue(ids.stream().distinct().count() == ids.size(), "ids are unique");
  }

  /** An order that needs no manual review, so placement stays within this transaction. */
  private String place() {
    return commandBus.send(
        new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 1, 1000, "USD"))));
  }
}
