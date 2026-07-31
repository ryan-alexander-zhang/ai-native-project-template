package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.Collection;

/**
 * Port for publishing the domain events an aggregate recorded. After a use case persists an
 * aggregate it drains the aggregate's events and hands them here; the infrastructure layer supplies
 * the implementation (for example, an in-process dispatcher or a transactional outbox).
 *
 * <p>The drain belongs to the <strong>repository</strong>: its {@code save} calls {@link
 * #publishAndClear(AbstractAggregateRoot)} after persisting the root, inside the command's
 * transaction. This keeps the "who changed?" question answered by whoever just saved the aggregate
 * — no ambient, thread-scoped change tracker is needed. Because publishing runs on the same
 * transaction as the save, a transactional implementation (an outbox row, or an
 * {@code @TransactionalEventListener}) still commits or rolls back atomically with the state
 * change.
 *
 * <p>A command handler must <em>not</em> call it: an aggregate's recorded events are not published
 * until something drains them, and nothing detects a missed drain — no exception, no log, no outbox
 * row. Leaving the call to the handler makes losing a domain fact a matter of remembering one line,
 * and the loss shows up far downstream (a process manager that never advances, a projection that
 * never updates). Keeping the single call site in the repository removes that possibility.
 */
public interface DomainEvents {

  void publish(DomainEvent event);

  default void publishAll(Collection<? extends DomainEvent> events) {
    events.forEach(this::publish);
  }

  /**
   * Publish the events an aggregate recorded, taking them off the aggregate first so a later save
   * does not re-publish. Call this right after persisting the root, within the same transaction.
   *
   * <p>The events are drained <em>before</em> publishing rather than cleared after, so that a
   * listener which records another event on the same aggregate during publication does not have it
   * discarded by the clear. If publishing throws, the drained events are gone from the aggregate —
   * which costs nothing, because the transaction this runs in rolls back and the instance is
   * discarded with it.
   *
   * <p>Recording an event on <em>this</em> aggregate from inside a listener is refused rather than
   * quietly accepted: the root has already been persisted by the time this runs, so the state
   * change such an event announces was never written. Publishing it would describe something that
   * did not happen; dropping it silently is how a domain event goes missing. Change the aggregate
   * before it is saved, or react in a separate use case.
   *
   * @throws IllegalStateException if the aggregate recorded new events while these were publishing
   */
  default void publishAndClear(AbstractAggregateRoot<?> aggregate) {
    publishAll(aggregate.drainDomainEvents());
    if (!aggregate.domainEvents().isEmpty()) {
      throw new IllegalStateException(
          "a listener recorded "
              + aggregate.domainEvents().size()
              + " further domain event(s) on "
              + aggregate.getClass().getSimpleName()
              + " while its events were being published, but the aggregate was already persisted,"
              + " so the state those events announce was never written. Make the change before the"
              + " aggregate is saved, or handle it as a separate use case.");
    }
  }
}
