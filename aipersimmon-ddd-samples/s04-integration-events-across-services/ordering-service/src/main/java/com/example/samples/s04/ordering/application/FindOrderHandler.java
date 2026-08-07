package com.example.samples.s04.ordering.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s04.ordering.domain.OrderId;
import com.example.samples.s04.ordering.domain.Orders;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Loads the aggregate and projects it, which is what {@code Query} permits for a single-entity read
 * — the part that matters is that it happens <em>here</em>, behind the bus, rather than in the
 * controller. The endpoint used to hold {@code Orders} itself, which put the read outside the
 * transaction and outside every interceptor the bus runs.
 *
 * <p>Empty rather than an exception, because the endpoint answers 404 and a missing order is not a
 * failure of the query.
 */
@Component
class FindOrderHandler implements QueryHandler<FindOrder, Optional<OrderView>> {

  private final Orders orders;

  FindOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Optional<OrderView> handle(FindOrder query) {
    return orders
        .find(new OrderId(query.orderId()))
        .map(
            order ->
                new OrderView(
                    order.id().value(),
                    order.customerId(),
                    order.lines().stream()
                        .map(line -> new OrderView.LineView(line.sku(), line.quantity()))
                        .toList()));
  }
}
