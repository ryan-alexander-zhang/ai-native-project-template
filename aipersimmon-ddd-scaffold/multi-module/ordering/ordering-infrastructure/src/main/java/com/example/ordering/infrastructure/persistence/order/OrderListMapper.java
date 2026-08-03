package com.example.ordering.infrastructure.persistence.order;

import com.example.ordering.application.order.OrderListItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * The read side's one statement: a customer's orders with their totals, newest first.
 *
 * <p>It reads the row and nothing else. The write side would have to load each order, rebuild its
 * lines and re-check its invariants to arrive at the same numbers, and then discard all of it — a
 * page of fifty orders means fifty aggregates nobody is going to change.
 *
 * <p><strong>The total is read, not recomputed.</strong> This statement used to derive it — {@code
 * SUM(l.quantity * l.unit_minor)} over a {@code LEFT JOIN}, with {@code MAX(l.currency)} for the
 * currency — which made "total = Σ line subtotals" a rule with two independent implementations,
 * only one of which knew its preconditions. {@code MAX(currency)} in particular was a guess rather
 * than a rule: it says nothing about an order having one currency, it just picks one, so
 * mixed-currency rows would have been summed across currencies and displayed, while the domain
 * refuses to construct them at all.
 *
 * <p>Skipping the aggregate on a read is right; recomputing its rules on a read is not. The total
 * stops changing once the order is placed, so it is frozen into the row by {@code
 * MyBatisOrders.toRow} and simply selected here. The join and the {@code GROUP BY} went with it,
 * which is why the query below touches one table.
 *
 * <p><strong>Newest first, by id.</strong> Order ids are UUIDv7, so ordering by id descending is
 * ordering by creation time descending, and the cursor is simply the last id of the previous page:
 * {@code id < :after}. No {@code created_at} column, no secondary sort to break ties, no offset to
 * re-scan. This only works because ids are time-ordered — with random UUIDs this query would need
 * its own timestamp column and index.
 *
 * <p><strong>Two separate things hold this up, and losing either is silent.</strong> The
 * <em>correctness</em> of the cursor — no repeats, no gaps, however many orders arrive mid-walk —
 * comes from the time-ordered id above. The <em>performance</em> of the cursor comes from an index
 * that turns this whole statement into one range scan: {@code orders_by_customer_newest_first} on
 * {@code (tenant_id, customer_id, id DESC)} ({@code V4}), which is exactly the predicate below plus
 * the interceptor's tenant column plus the sort — and, since the join went away with the derived
 * total, now covers the whole statement. Without them the query still returns the right page — it
 * just reads the whole table to find it, which is the cost cursor paging exists to avoid. A
 * functional test cannot tell those two apart, so {@code OrderListPagingTest} asserts the query
 * plan as well as the pages.
 *
 * <p><strong>Tenancy is not written here on purpose.</strong> {@code ordering.orders} is in {@code
 * aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables}, so the tenant-line interceptor adds {@code
 * tenant_id = ?} as this statement is prepared. A hand-written predicate here would be a second,
 * silently divergent copy of that rule.
 */
@Mapper
public interface OrderListMapper {

  /**
   * @param customerId whose orders to list
   * @param after the previous page's last id, or null for the first page
   * @param limit how many rows to fetch (the caller asks for one more than the page size to learn
   *     whether a further page exists)
   */
  @Select(
      """
      <script>
      SELECT o.id          AS id,
             o.status      AS status,
             o.total_minor AS totalMinor,
             o.currency    AS currency
        FROM ordering.orders o
       WHERE o.customer_id = #{customerId}
       <if test="after != null"> AND o.id &lt; #{after} </if>
       ORDER BY o.id DESC
       LIMIT #{limit}
      </script>
      """)
  List<OrderListItem> byCustomer(
      @Param("customerId") String customerId,
      @Param("after") String after,
      @Param("limit") int limit);
}
