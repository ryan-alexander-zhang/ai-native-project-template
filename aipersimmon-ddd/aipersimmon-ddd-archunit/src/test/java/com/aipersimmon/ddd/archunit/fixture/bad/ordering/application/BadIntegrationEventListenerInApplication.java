package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.bad.ordering.api.BadStockReserved;
import org.springframework.context.event.EventListener;

/**
 * Violates integration-event subscriber placement: an integration-event {@code @EventListener} in
 * the application layer. Receiving a cross-context event off a transport is inbound-adapter work;
 * this belongs in the adapter layer.
 */
public class BadIntegrationEventListenerInApplication {

  @EventListener
  public void on(BadStockReserved event) {
    // should live in an inbound adapter
  }
}
