package com.example.samples.s27.customer.infrastructure;

import com.example.samples.s27.customer.application.MarketingConsents;
import com.example.samples.s27.customer.domain.CustomerId;
import org.springframework.stereotype.Repository;

/** Three statements. */
@Repository
class MyBatisMarketingConsents implements MarketingConsents {

  private final MarketingConsentMapper mapper;

  MyBatisMarketingConsents(MarketingConsentMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void grant(CustomerId customerId, String note) {
    mapper.insert(customerId.value(), note);
  }

  @Override
  public long countFor(CustomerId customerId) {
    return mapper.countFor(customerId.value());
  }

  @Override
  public int forget(CustomerId customerId) {
    return mapper.deleteFor(customerId.value());
  }
}
