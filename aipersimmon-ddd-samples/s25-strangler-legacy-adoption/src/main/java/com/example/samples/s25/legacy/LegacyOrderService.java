package com.example.samples.s25.legacy;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monolith. One service, hand-written SQL, no framework module of any kind — and that last part is the
 * point rather than an omission.
 *
 * <p><strong>Nothing in this package uses the library.</strong> No aggregate, no repository base class, no
 * command bus, no outbox. That is what "legacy" means here: it predates the decision to adopt anything, and
 * the whole scenario is about what can be done <em>without</em> rewriting it. A sample whose legacy side
 * quietly used the library's building blocks would be answering a question nobody has.
 *
 * <p>Five methods, and they are written the way this code is actually written:
 *
 * <ul>
 *   <li>read-modify-write with no optimistic locking, because there is no version column to check;
 *   <li>rules expressed as {@code if} statements next to the SQL, or as {@code WHERE} clauses, or not at all;
 *   <li>{@code updated_at} maintained by hand, and {@link #addNote} forgets — which is authentic and is also
 *       the kind of thing that makes a legacy table's own timestamps untrustworthy for reconciliation;
 *   <li>one method ({@link #approveRefund}) that carries a real business rule, buried.
 * </ul>
 *
 * <p>The refund methods are the ones this sample strangles. After {@code s25.refunds.route=NEW_WRITES} they
 * delegate to the new context and keep their signatures, so every existing caller is unaffected — see
 * {@code LegacyRefundEntryPoint}. Their bodies stay here, unreachable, until the done criterion is met and
 * they are deleted; keeping them is what makes the change revertible with a config value rather than a
 * rollback.
 */
@Service
public class LegacyOrderService {

  private final JdbcTemplate jdbc;

  public LegacyOrderService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------------------------------------------
  // legacy_orders — four writers. High fan-in; see LegacyFanInTest for why that makes it a bad first pick.
  // ---------------------------------------------------------------------------------------------------

  /** @return the id the database assigned, which is how identity works around here */
  @Transactional
  public long placeOrder(String customerRef, long totalCents) {
    return jdbc.queryForObject(
        "INSERT INTO legacy_orders (customer_ref, status, total_cents) VALUES (?, 'NEW', ?)"
            + " RETURNING id",
        Long.class,
        customerRef,
        totalCents);
  }

  @Transactional
  public void markPaid(long orderId) {
    jdbc.update(
        "UPDATE legacy_orders SET status = 'PAID', updated_at = now() WHERE id = ? AND status = 'NEW'",
        orderId);
  }

  @Transactional
  public void markShipped(long orderId) {
    jdbc.update(
        "UPDATE legacy_orders SET status = 'SHIPPED', updated_at = now() WHERE id = ? AND status = 'PAID'",
        orderId);
  }

  @Transactional
  public void cancel(long orderId) {
    jdbc.update(
        "UPDATE legacy_orders SET status = 'CANCELLED', updated_at = now() WHERE id = ?", orderId);
  }

  /** Forgets {@code updated_at}. Left as found. */
  @Transactional
  public void addNote(long orderId, String note) {
    jdbc.update("UPDATE legacy_orders SET notes = ? WHERE id = ?", note, orderId);
  }

  // ---------------------------------------------------------------------------------------------------
  // legacy_order_items — two writers.
  // ---------------------------------------------------------------------------------------------------

  @Transactional
  public void addItem(long orderId, String sku, int qty, long unitCents) {
    jdbc.update(
        "INSERT INTO legacy_order_items (order_id, sku, qty, unit_cents) VALUES (?, ?, ?, ?)",
        orderId,
        sku,
        qty,
        unitCents);
    jdbc.update(
        "UPDATE legacy_orders SET total_cents = total_cents + ?, updated_at = now() WHERE id = ?",
        qty * unitCents,
        orderId);
  }

  @Transactional
  public void removeItem(long itemId) {
    jdbc.update("DELETE FROM legacy_order_items WHERE id = ?", itemId);
  }

  // ---------------------------------------------------------------------------------------------------
  // legacy_refunds — one writer of substance, and the rules are real. The first aggregate.
  // ---------------------------------------------------------------------------------------------------

  /**
   * Raise a refund. Two rules, both expressed as SQL or as nothing at all.
   *
   * <p>Worth reading as an example of why the rules are worth moving: "no refund larger than the order" is a
   * comparison against a value read in the same method with no lock, and "at most one open refund" is not
   * checked here at all — there is a partial index nobody added. Both hold most of the time.
   */
  @Transactional
  public long raiseRefund(long orderId, long amountCents, String reason) {
    Map<String, Object> order =
        jdbc.queryForMap("SELECT status, total_cents FROM legacy_orders WHERE id = ?", orderId);
    if ("CANCELLED".equals(order.get("status"))) {
      throw new IllegalStateException("cannot refund a cancelled order");
    }
    long total = ((Number) order.get("total_cents")).longValue();
    if (amountCents > total) {
      throw new IllegalStateException("refund exceeds order total");
    }
    return jdbc.queryForObject(
        "INSERT INTO legacy_refunds (order_id, amount_cents, reason, state, public_id)"
            + " VALUES (?, ?, ?, 'OPEN', gen_random_uuid()) RETURNING id",
        Long.class,
        orderId,
        amountCents,
        reason);
  }

  /**
   * Approve one. The read-modify-write with nothing to arbitrate two of them.
   *
   * <p>The {@code WHERE state = 'OPEN'} makes it <em>almost</em> safe, which is the most dangerous kind of
   * almost: two approvals racing produce one update and one silent no-op, and the caller of the no-op is told
   * nothing. That is the behaviour the new aggregate replaces with a refusal.
   */
  @Transactional
  public void approveRefund(long refundId, String approvedBy) {
    int updated =
        jdbc.update(
            "UPDATE legacy_refunds SET state = 'APPROVED', approved_by = ?, updated_at = now()"
                + " WHERE id = ? AND state = 'OPEN'",
            approvedBy,
            refundId);
    if (updated == 0) {
      // Nothing is thrown. The caller cannot tell "already approved" from "no such refund".
      return;
    }
  }

  // ---------------------------------------------------------------------------------------------------
  // Reads. These are the last thing to go, and they are the reason the ACL exists.
  // ---------------------------------------------------------------------------------------------------

  public LegacyOrderRecord findOrder(long orderId) {
    return jdbc.queryForObject(
        "SELECT id, customer_ref, status, total_cents FROM legacy_orders WHERE id = ?",
        (rs, row) ->
            new LegacyOrderRecord(
                rs.getLong("id"),
                rs.getString("customer_ref"),
                rs.getString("status"),
                rs.getLong("total_cents")),
        orderId);
  }

  public List<LegacyRefundRecord> findRefunds(long orderId) {
    return jdbc.query(
        "SELECT id, order_id, amount_cents, reason, state FROM legacy_refunds WHERE order_id = ?"
            + " ORDER BY id",
        (rs, row) ->
            new LegacyRefundRecord(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("amount_cents"),
                rs.getString("reason"),
                rs.getString("state")),
        orderId);
  }
}
