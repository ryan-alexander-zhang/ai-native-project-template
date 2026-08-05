package com.aipersimmon.ddd.archunit.fixture.aftercommit.ordering.adapter;

import com.aipersimmon.ddd.archunit.fixture.aftercommit.ordering.domain.AfterCommitOrderPlaced;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The one violating method in the isolated after-commit fixture: an after-commit domain-event
 * subscriber in the adapter layer, unmarked. It exists so the two domain-event rules can be
 * measured against a package where their expected violation set is exactly one method.
 */
public class AfterCommitSubscriberInAdapter {

  @TransactionalEventListener
  public void on(AfterCommitOrderPlaced event) {
    // should be in the application layer, and should be marked
  }
}
