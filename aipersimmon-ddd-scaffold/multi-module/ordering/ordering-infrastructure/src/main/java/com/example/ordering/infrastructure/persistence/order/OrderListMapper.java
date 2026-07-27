package com.example.ordering.infrastructure.persistence.order;

import com.example.ordering.application.order.OrderListItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * The read side's one statement: a customer's orders with their totals, newest first.
 *
 * <p>It joins and aggregates in SQL because that is what SQL is for. The write side would have to
 * load each order, rebuild its lines and re-check its invariants to arrive at the same numbers, and
 * then discard all of it — a page of fifty orders means fifty aggregates nobody is going to change.
 *
 * <p><strong>Newest first, by id.</strong> Order ids are UUIDv7, so ordering by id descending is
 * ordering by creation time descending, and the cursor is simply the last id of the previous page:
 * {@code id < :after}. No {@code created_at} column, no secondary sort to break ties, no offset to
 * re-scan. This only works because ids are time-ordered — with random UUIDs this query would need
 * its own timestamp column and index.
 *
 * <p><strong>Tenancy is not written here on purpose.</strong> {@code ordering.orders} and {@code
 * ordering.order_lines} are both in {@code aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables}, so
 * the tenant-line interceptor adds {@code tenant_id = ?} to each of them as this statement is
 * prepared. A hand-written predicate here would be a second, silently divergent copy of that rule.
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
      SELECT o.id                                AS id,
             o.status                            AS status,
             COALESCE(SUM(l.quantity * l.unit_minor), 0) AS totalMinor,
             MAX(l.currency)                     AS currency
        FROM ordering.orders o
        LEFT JOIN ordering.order_lines l ON l.order_id = o.id
       WHERE o.customer_id = #{customerId}
       <if test="after != null"> AND o.id &lt; #{after} </if>
       GROUP BY o.id, o.status
       ORDER BY o.id DESC
       LIMIT #{limit}
      </script>
      """)
  List<OrderListItem> byCustomer(
      @Param("customerId") String customerId,
      @Param("after") String after,
      @Param("limit") int limit);
}
