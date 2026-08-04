package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import java.util.List;
import org.springframework.stereotype.Component;

/** One indexed read of the projection. */
@Component
class TopSellersHandler implements QueryHandler<TopSellersQuery, List<TopSeller>> {

  private final SalesBoard board;

  TopSellersHandler(SalesBoard board) {
    this.board = board;
  }

  @Override
  public List<TopSeller> handle(TopSellersQuery query) {
    return board.top(query.limit());
  }
}
