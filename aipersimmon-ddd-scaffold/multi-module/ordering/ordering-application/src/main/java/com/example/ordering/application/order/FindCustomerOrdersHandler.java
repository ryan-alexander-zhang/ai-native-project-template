package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.aipersimmon.ddd.cqrs.page.Slice;
import org.springframework.stereotype.Component;

/**
 * Answers {@link FindCustomerOrders} from the read port. The only judgement it makes is the page
 * size: a caller asking for 10 000 rows gets {@link #MAX_SIZE}, and one asking for nothing gets
 * {@link #DEFAULT_SIZE}. Clamping belongs here rather than in the controller because it is a
 * property of the use case — every transport that lists orders should get the same ceiling — and
 * not in the SQL, where it would be invisible.
 */
@Component
public class FindCustomerOrdersHandler
    implements QueryHandler<FindCustomerOrders, Slice<OrderListItem>> {

  static final int DEFAULT_SIZE = 20;
  static final int MAX_SIZE = 100;

  private final OrderQueries orders;

  public FindCustomerOrdersHandler(OrderQueries orders) {
    this.orders = orders;
  }

  @Override
  public Slice<OrderListItem> handle(FindCustomerOrders query) {
    int size = query.size() <= 0 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
    return orders.byCustomer(query.customerId(), query.cursor(), size);
  }
}
