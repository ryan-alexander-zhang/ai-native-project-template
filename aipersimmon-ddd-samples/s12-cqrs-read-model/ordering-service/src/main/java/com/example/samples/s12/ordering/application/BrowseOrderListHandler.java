package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * One statement against one table, which is the entire payoff.
 *
 * <p>Worth comparing with what this query would be without the projection: order joined to lines, grouped,
 * and then the product names fetched from a different database in a different service — a join that cannot be
 * written. Everything expensive was moved to write time. That is the trade in one sentence, and §3 of the
 * companion document argues when it is worth making.
 */
@Component
class BrowseOrderListHandler implements QueryHandler<BrowseOrderList, List<OrderListItem>> {

  private final OrderListQueries queries;

  BrowseOrderListHandler(OrderListQueries queries) {
    this.queries = queries;
  }

  @Override
  public List<OrderListItem> handle(BrowseOrderList query) {
    return queries.recentFor(query.customerId(), query.limit());
  }
}
