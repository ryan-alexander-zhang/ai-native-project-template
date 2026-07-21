package com.aipersimmon.ddd.archunit.fixture.good.ordering.adapter;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.api.GoodStockReserved;
import org.springframework.context.event.EventListener;

/**
 * Well-placed integration-event subscriber: an inbound adapter reacting to another context's
 * published event, satisfying {@code integrationEventListenersShouldResideInAdapter}. It reads only
 * the published contract, so it touches no domain type.
 */
public class GoodStockReservedListener {

  @EventListener
  public void on(GoodStockReserved event) {
    // translate to a command and hand off inward — omitted
  }
}
