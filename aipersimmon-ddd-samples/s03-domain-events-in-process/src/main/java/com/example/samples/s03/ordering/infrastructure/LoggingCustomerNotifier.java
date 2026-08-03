package com.example.samples.s03.ordering.infrastructure;

import com.example.samples.s03.ordering.application.CustomerNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stands in for an email or push provider. The tests replace it with one that records and can fail. */
@Component
class LoggingCustomerNotifier implements CustomerNotifier {

  private static final Logger log = LoggerFactory.getLogger(LoggingCustomerNotifier.class);

  @Override
  public void orderConfirmedTo(String customerId, String orderId) {
    log.info("notified {} about order {}", customerId, orderId);
  }
}
