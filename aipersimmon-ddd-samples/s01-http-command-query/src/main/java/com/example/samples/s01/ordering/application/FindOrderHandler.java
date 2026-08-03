package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s01.ordering.domain.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * The read side. {@code QueryHandler.handle} takes no {@code CommandContext} — a read has no causal
 * chain to carry — and the query bus wraps it in no transaction, no logging and no tracing: the
 * library ships zero query interceptors, leaving that chain as the application's seam.
 */
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
