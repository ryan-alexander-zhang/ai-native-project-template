package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.aipersimmon.ddd.cqrs.page.Page;
import org.springframework.stereotype.Component;

/** Two handlers, one pager: the shapes differ, the meaning of "the next page" must not. */
@Component
class BrowseOrdersWithTotalsHandler
    implements QueryHandler<BrowseOrdersWithTotals, Page<OrderSummary>> {

  private final OrderPager pager;

  BrowseOrdersWithTotalsHandler(OrderPager pager) {
    this.pager = pager;
  }

  @Override
  public Page<OrderSummary> handle(BrowseOrdersWithTotals query) {
    return pager.page(query.page());
  }
}
