package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.ordering.domain.order.CancellableByCustomer;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers {@link FindOrder} with an {@link OrderSnapshot}. Kept minimal: it loads the aggregate and
 * maps it, which is the right trade for a single-order read whose shape follows the aggregate's.
 *
 * <p>A query that diverges from that shape reads the tables instead — see {@link OrderQueries} and
 * {@link FindCustomerOrders}, where a page of orders is one join with the totals summed in SQL.
 * Rehydrating fifty aggregates to render a list rebuilds fifty sets of lines and invariants that a
 * read is never going to use.
 *
 * <p>This is the single-entity exception the framework's {@code Query} contract names (read models
 * by default; a read whose shape follows the aggregate may load it, provided it mutates nothing) —
 * taken here deliberately, because {@code cancellableByCustomer} is a domain specification and
 * loading the aggregate keeps its one definition instead of re-deriving it from a status column in
 * a second place (issue-00150).
 */
@Component
public class FindOrderHandler implements QueryHandler<FindOrder, Optional<OrderSnapshot>> {

  private final Orders orders;

  public FindOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Optional<OrderSnapshot> handle(FindOrder query) {
    return orders.findById(new OrderId(query.orderId())).map(FindOrderHandler::toSnapshot);
  }

  private static OrderSnapshot toSnapshot(Order order) {
    Money total = order.total();
    // The specification answers here so the caller does not have to attempt the cancellation to
    // find out whether it would be allowed. The aggregate still decides at the moment of the
    // write — this is advice, not authorisation.
    boolean cancellable = new CancellableByCustomer(order.customerId()).isSatisfiedBy(order);
    return new OrderSnapshot(
        order.id().value(),
        order.customerId().value(),
        order.status(),
        total.amountMinor(),
        total.currency(),
        cancellable);
  }
}
