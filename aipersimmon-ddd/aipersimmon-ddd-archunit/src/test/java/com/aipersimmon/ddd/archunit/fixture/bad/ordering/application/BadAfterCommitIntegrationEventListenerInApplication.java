package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.api.BadStockReserved;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The third rule built on the same predicate, exercised in the meta-annotated spelling: an
 * integration-event subscriber in the application layer, declared with
 * {@code @TransactionalEventListener}. Receiving a cross-context event off a transport is
 * inbound-adapter work whichever annotation announces the subscription.
 */
public class BadAfterCommitIntegrationEventListenerInApplication {

  @TransactionalEventListener
  public void on(BadStockReserved event) {
    // should live in an inbound adapter
  }
}
