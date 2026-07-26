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
 * never updates). Keeping the single call site in the repository removes that possibility; see
 * {@code issue-00052}.
 */
public interface DomainEvents {

  void publish(DomainEvent event);

  default void publishAll(Collection<? extends DomainEvent> events) {
    events.forEach(this::publish);
  }

  /**
   * Publish the events an aggregate recorded, then clear them so a later save of the same aggregate
   * does not re-publish. Call this right after persisting the root, within the same transaction.
   */
  default void publishAndClear(AbstractAggregateRoot<?> aggregate) {
    publishAll(aggregate.domainEvents());
    aggregate.clearDomainEvents();
  }
}
