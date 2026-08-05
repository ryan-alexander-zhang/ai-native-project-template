package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodOrderPlaced;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The same well-placed subscriber as {@link GoodOrderPlacedHandler}, spelled the other way:
 * {@code @TransactionalEventListener}, which carries {@code @EventListener} as a meta-annotation
 * rather than directly. This is the after-commit form — the one to use for work that must not run
 * before the commit — and the placement rules used to be blind to it, so it is a fixture rather
 * than a comment.
 */
@DomainEventHandler
public class GoodOrderPlacedAfterCommitHandler {

  @TransactionalEventListener(phase = AFTER_COMMIT)
  public void on(GoodOrderPlaced event) {
    // notify outward once the placement is durable — omitted
  }
}
