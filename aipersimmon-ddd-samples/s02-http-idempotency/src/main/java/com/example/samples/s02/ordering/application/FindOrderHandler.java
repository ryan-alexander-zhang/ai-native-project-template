package com.example.samples.s02.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s02.ordering.domain.OrderingErrorCode;
import org.springframework.stereotype.Component;

/** The read side. */
@Component
class FindOrderHandler implements QueryHandler<FindOrder, OrderView> {

  private final OrderViews orderViews;

  FindOrderHandler(OrderViews orderViews) {
    this.orderViews = orderViews;
  }

  @Override
  public OrderView handle(FindOrder query) {
    return orderViews
        .findById(query.orderId())
        .orElseThrow(
            () ->
                new EntityNotFoundException(
                    OrderingErrorCode.ORDER_NOT_FOUND, "order " + query.orderId() + " not found"));
  }
}
