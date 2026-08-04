package com.example.samples.s07.payments.infrastructure;

import com.example.samples.s07.payments.application.StalePayments;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** The candidate scan. Ids and nothing else, so no state can travel from the scan into a command. */
@Repository
class MyBatisStalePayments implements StalePayments {

  private final PaymentMapper mapper;

  MyBatisStalePayments(PaymentMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<String> findUnsettled(Instant requestedBefore, int limit) {
    return mapper.selectUnsettledIds(requestedBefore.toString(), limit);
  }
}
