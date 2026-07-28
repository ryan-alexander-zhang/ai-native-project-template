package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessQuery;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stocks;
import com.example.ordering.application.order.CancelOwnOrder;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import com.example.ordering.process.fulfilment.OrderFulfilmentDefinition;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The race that making {@code READY_FOR_FULFILMENT} real opens, and which closing issue-00070
 * without handling would have turned into a stock leak.
 *
 * <p>Once the self-cancel window is genuinely reachable, it overlaps the reservation: the customer
 * can cancel while inventory is still working, and inventory answers a moment later for an order
 * that no longer exists. Nothing in the flow could previously happen — the order was already past
 * the window before the reservation request was even published — so nothing handled it.
 *
 * <p>Left unhandled it fails twice over. {@code BeginFulfilment} would find a {@code CANCELLED}
 * order, the aggregate would refuse the transition, and the effect relay would retry that command
 * until it dead-lettered. And the reserved stock would never come back, because the compensation
 * path is only entered from a payment failure — the same shape as the leak in issue-00094, arrived
 * at from the opposite direction.
 *
 * <p>What makes it tractable is that ordering already tells the process manager when an order is
 * cancelled: {@code OrderCancelledEvent} reaches it as an {@code OrderCancelled} input, which the
 * flow used to ignore at this step. It now remembers the cancellation and, when the reservation
 * turns up, releases it instead of proceeding — the cancellation stands, because it was made while
 * the order was still the customer's to cancel.
 *
 * <p>Relays are on and the deadline worker runs, because the point is the whole cascade settling on
 * its own.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.deadline-worker.poll-delay=200ms",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@Import(TestInfrastructure.class)
class SelfCancelDuringReservationTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);
  private static final TenantId TENANT = Tenants.of("acme");

  @Autowired CommandBus commandBus;
  @Autowired QueryBus queryBus;
  @Autowired Stocks stocks;
  @Autowired DefaultProcessQuery process;
  @Autowired JdbcTemplate jdbc;

  @Test
  void stockReservedForAnAlreadyCancelledOrderIsGivenBack() {
    seed();
    int stockBefore = available();

    // Placed and immediately cancelled — inside the window, before the reservation can answer.
    String orderId = TenantContext.runAs(TENANT, this::place);
    TenantContext.runAs(TENANT, () -> commandBus.send(new CancelOwnOrder(orderId, "CUST-RACE")));

    assertEquals("CANCELLED", TenantContext.runAs(TENANT, () -> status(orderId)));

    // Wait for the flow to finish rather than for the stock figure. Asserting the figure first
    // would pass at t=0, before inventory had reserved anything at all — the reservation is
    // asynchronous, so "stock is unchanged" is true both before it happens and after it is undone.
    await()
        .atMost(SETTLE)
        .untilAsserted(
            () -> {
              ProcessView view = processView(orderId);
              assertEquals(
                  ProcessLifecycle.COMPLETED,
                  view.lifecycle(),
                  "the flow must reach a terminal state, not park holding a reservation");
              assertEquals("ORDER_CANCELLED", view.outcome().orElseThrow().value());
            });

    // This is what makes the stock assertion mean something: the reservation really was made, and
    // really was released. Reaching ORDER_CANCELLED any other way would leave no reservation row.
    assertEquals(
        Boolean.TRUE,
        jdbc.queryForObject(
            "SELECT released FROM inventory.reservations WHERE tenant_id = ? AND order_id = ?",
            Boolean.class,
            TENANT.value(),
            orderId),
        "inventory did reserve for this order, and the flow handed it back");
    assertEquals(
        stockBefore,
        available(),
        "stock reserved for an order that was already cancelled has to come back; nothing else"
            + " will ever ask for it");

    assertEquals(
        "CANCELLED",
        TenantContext.runAs(TENANT, () -> status(orderId)),
        "the cancellation stands — BeginFulfilment must not revive a cancelled order");

    assertEquals(
        0,
        (int) jdbc.queryForObject("SELECT count(*) FROM aipersimmon_dead_letter", Integer.class),
        "and it must get there without dead-lettering a BeginFulfilment nobody can apply");
  }

  private void seed() {
    jdbc.update(
        "INSERT INTO ordering.customers (id, name, credit_minor, currency, tenant_id)"
            + " VALUES ('CUST-RACE', 'Acme', 10000000, 'USD', ?)"
            + " ON CONFLICT (tenant_id, id) DO NOTHING",
        TENANT.value());
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id) VALUES ('SKU-RACE', 500, ?)"
            + " ON CONFLICT (tenant_id, sku) DO UPDATE SET available = 500",
        TENANT.value());
  }

  private String place() {
    return commandBus.send(
        new PlaceOrder("CUST-RACE", List.of(new PlaceOrder.Line("SKU-RACE", 3, 100, "USD"))));
  }

  private int available() {
    return TenantContext.runAs(
        TENANT, () -> stocks.findBySku(new Sku("SKU-RACE")).orElseThrow().available());
  }

  private String status(String orderId) {
    return queryBus.ask(new FindOrder(orderId)).orElseThrow().status();
  }

  /** Bound to the tenant the flow was started under, or the lookup finds nothing. */
  private ProcessView processView(String orderId) {
    return TenantContext.runAs(
        TENANT,
        () ->
            process
                .findRef(OrderFulfilmentDefinition.PROCESS_TYPE, new ProcessBusinessKey(orderId))
                .flatMap(process::find)
                .orElseThrow());
  }
}
