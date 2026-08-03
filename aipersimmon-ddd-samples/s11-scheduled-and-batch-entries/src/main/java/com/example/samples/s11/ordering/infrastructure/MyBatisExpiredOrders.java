package com.example.samples.s11.ordering.infrastructure;

import com.example.samples.s11.ordering.application.ExpiredOrders;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** The candidate scan. Ids and nothing else, so no state can travel from the scan into a command. */
@Repository
class MyBatisExpiredOrders implements ExpiredOrders {

  private final OrderMapper mapper;

  MyBatisExpiredOrders(OrderMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<String> findExpired(Instant asOf, int limit) {
    return mapper.selectExpiredIds(asOf.toString(), limit);
  }
}
