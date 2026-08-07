package com.example.samples.s22.ordering.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s22.ordering.domain.OrderId;
import com.example.samples.s22.ordering.domain.Orders;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The read that lets an operator confirm what survived a quarantined message. Behind the bus, like
 * every other entry into this application — which is the property this sample cares about most,
 * since its subject is what happens when delivery goes wrong and the answer has to be trustworthy.
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
                    order.id().value(), order.customerId(), order.sku(), order.quantity()));
  }
}
