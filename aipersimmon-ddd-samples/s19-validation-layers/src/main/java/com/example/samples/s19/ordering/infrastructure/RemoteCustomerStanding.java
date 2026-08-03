package com.example.samples.s19.ordering.infrastructure;

import com.example.samples.s19.ordering.application.CustomerStanding;
import org.springframework.stereotype.Component;

/**
 * Stands in for a call into another context. It is deliberately shaped like a remote client — that is
 * the case the precheck's placement is designed for; a local lookup would not need it.
 */
@Component
class RemoteCustomerStanding implements CustomerStanding {

  @Override
  public boolean isBlocked(String customerId) {
    return customerId.startsWith("blocked");
  }
}
