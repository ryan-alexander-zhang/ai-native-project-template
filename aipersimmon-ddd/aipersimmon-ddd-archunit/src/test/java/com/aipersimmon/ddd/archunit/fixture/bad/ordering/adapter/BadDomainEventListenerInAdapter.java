package com.aipersimmon.ddd.archunit.fixture.bad.ordering.adapter;

import org.springframework.context.event.EventListener;

/**
 * Violates domain-event subscriber placement: a domain-event {@code @EventListener} in the
 * interface/adapter layer. Its argument {@link BadEventInAdapter} is a domain event, so this
 * subscription belongs in the application layer, not an adapter.
 */
public class BadDomainEventListenerInAdapter {

  @EventListener
  public void on(BadEventInAdapter event) {
    // should live in the application layer
  }
}
