package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.inventory.application.stock.ReleaseStock;
import com.example.inventory.application.stock.ReserveStock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A reservation reserves every line or changes nothing (issue-00094, review finding B2).
 *
 * <p>The handler's contract has two halves and only one of them was ever tested. That a failed
 * reservation reports {@code StockReservationFailed} is exercised all over the suite, by every
 * compensation flow. That a failed reservation leaves the stock <em>untouched</em> was not
 * exercised anywhere — and it was not true: the handler deducted and saved line by line, then
 * swallowed the {@code DomainException} so the transaction committed. A line that failed after an
 * earlier line had already been saved stranded that earlier deduction permanently, because a
 * release needs a {@code Reservation} id and no {@code Reservation} was written.
 *
 * <p>Both halves are asserted together here on purpose. Making the stock survive is easy if you are
 * allowed to roll the whole transaction back; the hard part is doing it while the failure event
 * still commits, and a test that only checked the stock could be passed by a fix that lost the
 * event.
 *
 * <p>Driven straight on the command bus rather than through ordering, which is the point of
 * issue-00076: inventory must give a well-defined answer to any command shaped like this one,
 * whoever sends it. Reaching it through {@code PlaceOrder} would only prove that ordering does not
 * currently send the shape that breaks it.
 *
 * <p>Shares its application context (and therefore its containers) with the other tests carrying
 * this exact {@code properties} block — see issue-00092 for why that matters.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.relay.enabled=false",
    })
@Import(TestInfrastructure.class)
class StockReservationAtomicityTest {

  private static final TenantId TENANT = Tenants.of("acme");

  @Autowired CommandBus commandBus;

  @Autowired JdbcTemplate jdbc;

  /**
   * The deduction that used to vanish. Two lines naming one SKU, each individually affordable but
   * not both: the old handler validated both against the untouched row, then reserved and saved the
   * first, re-read the row it had just written, and failed the second against the reduced figure.
   * The catch turned that into an event and the transaction committed — six units gone, with no
   * reservation to release them.
   */
  @Test
  void aRepeatedSkuDeductsNothingWhenTheCombinedQuantityIsTooLarge() {
    stock("SKU-LEAK", 10);

    reserve(
        "order-leak", new ReserveStock.Line("SKU-LEAK", 6), new ReserveStock.Line("SKU-LEAK", 6));

    assertEquals(
        10,
        availableOf("SKU-LEAK"),
        "a reservation that failed must leave every unit where it was — 6 of these were being"
            + " deducted and stranded, with no Reservation able to release them");
    assertEquals(0, reservationsFor("order-leak"), "and no reservation may exist for it");
    assertTrue(
        failureWasReported("order-leak"),
        "while the failure must still reach the process manager — rolling the whole transaction"
            + " back would take the event with it");
  }

  /** The same guarantee across distinct SKUs: the affordable line must not be taken either. */
  @Test
  void anUnaffordableLineLeavesTheAffordableOnesUntouched() {
    stock("SKU-PLENTY", 5);
    stock("SKU-SHORT", 1);

    reserve(
        "order-short",
        new ReserveStock.Line("SKU-PLENTY", 5),
        new ReserveStock.Line("SKU-SHORT", 5));

    assertEquals(5, availableOf("SKU-PLENTY"), "all or nothing means nothing here");
    assertEquals(1, availableOf("SKU-SHORT"));
    assertEquals(0, reservationsFor("order-short"));
    assertTrue(failureWasReported("order-short"));
  }

  /**
   * Repeated SKUs are merged rather than rejected, so the affordable case simply works and holds
   * one line for the summed quantity — the caller's intent is unambiguous.
   */
  @Test
  void aRepeatedSkuIsHeldAsOneLineForTheSummedQuantity() {
    stock("SKU-MERGE", 10);

    reserve(
        "order-merge",
        new ReserveStock.Line("SKU-MERGE", 2),
        new ReserveStock.Line("SKU-MERGE", 3));

    assertEquals(5, availableOf("SKU-MERGE"), "2 + 3 came off the one SKU");
    assertEquals(1, reservationsFor("order-merge"), "one reservation");
    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory.reservation_lines l"
                    + " JOIN inventory.reservations r"
                    + "   ON r.tenant_id = l.tenant_id AND r.id = l.reservation_id"
                    + " WHERE r.order_id = ? AND l.sku = ?",
                Integer.class,
                "order-merge",
                "SKU-MERGE"),
        "held as a single line, not two");
    assertEquals(
        5,
        (int)
            jdbc.queryForObject(
                "SELECT l.quantity FROM inventory.reservation_lines l"
                    + " JOIN inventory.reservations r"
                    + "   ON r.tenant_id = l.tenant_id AND r.id = l.reservation_id"
                    + " WHERE r.order_id = ? AND l.sku = ?",
                Integer.class,
                "order-merge",
                "SKU-MERGE"));
  }

  /**
   * A status-only save leaves the child rows alone (issue-00090).
   *
   * <p>Asserted with PostgreSQL's {@code xmin} — the id of the transaction that last wrote each
   * row. Rewriting a row produces a new version with a new {@code xmin}, so an unchanged value is
   * direct evidence the rows were not touched. Nothing else here could show it: a
   * delete-and-reinsert of identical data is invisible in the data itself, which is exactly why the
   * wasted work went unnoticed.
   */
  @Test
  void aLifecycleTransitionDoesNotRewriteTheReservationLines() {
    stock("SKU-KEEP", 100);
    reserve("order-keep", new ReserveStock.Line("SKU-KEEP", 4));

    List<Long> before = lineVersionsFor("order-keep");
    assertEquals(1, before.size(), "one held line to watch");

    // Releasing changes only the reservation header's `released` flag; its held quantities are
    // untouched, so saveChildren has nothing to do.
    TenantContext.runAs(TENANT, () -> commandBus.send(new ReleaseStock(reservationIdFor())));

    assertEquals(
        before,
        lineVersionsFor("order-keep"),
        "the reservation's lines must not be deleted and re-inserted to arrive at the rows that"
            + " were already there");
  }

  /** Each held line's row version — PostgreSQL's own last-writer transaction id. */
  private List<Long> lineVersionsFor(String orderId) {
    return jdbc.queryForList(
        "SELECT l.xmin::text::bigint FROM inventory.reservation_lines l"
            + " JOIN inventory.reservations r"
            + "   ON r.tenant_id = l.tenant_id AND r.id = l.reservation_id"
            + " WHERE r.tenant_id = ? AND r.order_id = ?"
            + " ORDER BY l.sku",
        Long.class,
        TENANT.value(),
        orderId);
  }

  private String reservationIdFor() {
    return jdbc.queryForObject(
        "SELECT id FROM inventory.reservations WHERE tenant_id = ? AND order_id = ?",
        String.class,
        TENANT.value(),
        "order-keep");
  }

  private void reserve(String orderId, ReserveStock.Line... lines) {
    TenantContext.runAs(TENANT, () -> commandBus.send(new ReserveStock(orderId, List.of(lines))));
  }

  /**
   * Seeds a SKU for this tenant with the raw template, which the tenant interceptor leaves alone.
   */
  private void stock(String sku, int available) {
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id) VALUES (?, ?, ?)"
            + " ON CONFLICT (tenant_id, sku) DO UPDATE SET available = EXCLUDED.available",
        sku,
        available,
        TENANT.value());
  }

  private int availableOf(String sku) {
    return jdbc.queryForObject(
        "SELECT available FROM inventory.stocks WHERE tenant_id = ? AND sku = ?",
        Integer.class,
        TENANT.value(),
        sku);
  }

  private int reservationsFor(String orderId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM inventory.reservations WHERE tenant_id = ? AND order_id = ?",
        Integer.class,
        TENANT.value(),
        orderId);
  }

  /** The relay is off in this context, so the event is still sitting in the outbox to be found. */
  private boolean failureWasReported(String orderId) {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM aipersimmon_outbox"
                + " WHERE type LIKE '%StockReservationFailed%' AND payload LIKE ?",
            Integer.class, "%" + orderId + "%");
    return rows != null && rows > 0;
  }
}
