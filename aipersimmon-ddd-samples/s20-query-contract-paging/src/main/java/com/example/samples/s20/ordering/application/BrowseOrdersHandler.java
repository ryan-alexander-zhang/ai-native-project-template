package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.aipersimmon.ddd.cqrs.page.Slice;
import org.springframework.stereotype.Component;

/**
 * {@code handle} takes no {@code CommandContext} — a read mints no message identity and joins no
 * causal chain — and the query bus wraps it in no transaction and no interceptors, because the
 * library registers none.
 */
@Component
class BrowseOrdersHandler implements QueryHandler<BrowseOrders, Slice<OrderSummary>> {

  private final OrderPager pager;

  BrowseOrdersHandler(OrderPager pager) {
    this.pager = pager;
  }

  @Override
  public Slice<OrderSummary> handle(BrowseOrders query) {
    return pager.slice(query.page());
  }
}
