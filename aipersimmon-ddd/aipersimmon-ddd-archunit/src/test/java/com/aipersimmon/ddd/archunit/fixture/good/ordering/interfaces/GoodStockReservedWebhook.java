package com.aipersimmon.ddd.archunit.fixture.good.ordering.interfaces;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.api.GoodStockReserved;
import org.springframework.context.event.EventListener;

/**
 * A well-placed integration-event subscriber that happens to live under {@code ..interfaces..}
 * rather than {@code ..adapter..}.
 *
 * <p>It is the positive half of the layer-name widening, and the half that is easy to forget:
 * {@code integrationEventListenersShouldResideInAdapter} must <em>accept</em> this, not merely stop
 * being blind to the package. Before the rule read {@link com.aipersimmon.ddd.archunit.Layers} it
 * would have reported this class for sitting outside {@code ..adapter..} — a project laid out with
 * {@code interfaces} had no placement that satisfied it.
 */
public class GoodStockReservedWebhook {

  @EventListener
  public void on(GoodStockReserved event) {
    // an inbound adapter translates and hands off inward; nothing to do in a fixture
  }
}
