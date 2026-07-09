package com.acme.samples.s2.ordering.infrastructure.command;

import com.acme.samples.s2.shared.AggregateRoot;
import com.acme.samples.s2.shared.Command;
import com.acme.samples.s2.shared.CommandBus;
import com.acme.samples.s2.shared.DomainEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * UnitOfWork decorator (analysis-00005 §5.1): opens a transaction around the
 * handler, then — still inside that transaction — drains the aggregates the handler
 * registered and publishes their domain events. Because publication is in-tx, the
 * {@code @EventListener} that writes the outbox and updates the projection commits
 * atomically with the aggregate. Equivalent to MediatR's UnitOfWorkBehavior /
 * Grzybek's UnitOfWork decorator; the {@link ThreadLocalAggregateChanges} collector
 * stands in for EF's ChangeTracker (MyBatis has none).
 */
public class TransactionalCommandBus implements CommandBus {

    private final CommandBus delegate;
    private final TransactionTemplate tx;
    private final DomainEvents domainEvents;
    private final ThreadLocalAggregateChanges changes;

    public TransactionalCommandBus(CommandBus delegate, PlatformTransactionManager txManager,
                                   DomainEvents domainEvents, ThreadLocalAggregateChanges changes) {
        this.delegate = delegate;
        this.tx = new TransactionTemplate(txManager);
        this.domainEvents = domainEvents;
        this.changes = changes;
    }

    @Override
    public <R> R dispatch(Command<R> command) {
        try {
            return tx.execute(status -> {
                R result = delegate.dispatch(command);
                for (AggregateRoot aggregate : changes.drain()) {
                    domainEvents.publish(aggregate.domainEvents());
                    aggregate.clearDomainEvents();
                }
                return result;
            });
        } finally {
            changes.clear();
        }
    }
}
