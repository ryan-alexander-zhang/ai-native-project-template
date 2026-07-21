package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;

/**
 * Well-placed query handler: it answers a query from a read model and lives in the application
 * layer, satisfying {@code commandAndQueryHandlersShouldResideInApplication} on the read side.
 */
public class GoodFindOrderHandler implements QueryHandler<GoodFindOrder, String> {

  @Override
  public String handle(GoodFindOrder query) {
    return query.orderId();
  }
}
