package com.aipersimmon.ddd.archunit.fixture.bad.ordering.adapter;

import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Violates both domain-event subscriber rules at once, in the spelling that used to escape them: an
 * after-commit domain-event subscriber sitting in the adapter layer with no
 * {@code @DomainEventHandler} marker. {@code @TransactionalEventListener} carries
 * {@code @EventListener} as a meta-annotation, so a rule that checked only direct presence reported
 * nothing here.
 */
public class BadAfterCommitDomainEventListenerInAdapter {

  @TransactionalEventListener
  public void on(BadEventInAdapter event) {
    // should live in the application layer, and should be marked
  }
}
