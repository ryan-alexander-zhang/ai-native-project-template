package com.example.samples.s23.ordering.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s23.ordering.domain.OrderId;
import com.example.samples.s23.ordering.domain.Orders;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Projects the order, including the undecided {@code handling} the backfill has not reached yet.
 *
 * <p>Doing it here rather than in the endpoint is what makes the migration observable from one
 * place: while the backfill runs, "what does a caller see for a row that has not been decided" is
 * answered by this method, not by whichever controller happens to assemble a response.
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
                    order.sku(),
                    order.quantity(),
                    order.shipTo().street(),
                    order.shipTo().city(),
                    order.handling().map(Enum::name).orElse(null)));
  }
}
