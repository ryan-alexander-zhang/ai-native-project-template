package com.example.samples.s24.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s24.ordering.domain.Order;
import com.example.samples.s24.ordering.domain.OrderId;
import com.example.samples.s24.ordering.domain.OrderingErrorCode;
import com.example.samples.s24.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * The order as it was priced.
 *
 * <p>It reports the discount that was applied and does <strong>not</strong> re-quote the coupon. Re-quoting on read would
 * make the price of a placed order depend on whether the coupon has since expired, which is a different order every time
 * somebody looks at it. What was agreed is a fact of this context; the coupon's current state is not.
 */
@Component
class OrderQueryHandler implements QueryHandler<OrderQuery, OrderTotals> {

  private final Orders orders;

  OrderQueryHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public OrderTotals handle(OrderQuery query) {
    Order order =
        orders
            .find(new OrderId(query.orderId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "no order " + query.orderId()));
    return new OrderTotals(
        order.id().value(),
        order.gross().minor(),
        order.discount().minor(),
        order.total().minor(),
        order.gross().currency(),
        order.couponCode().orElse(null),
        null);
  }
}
