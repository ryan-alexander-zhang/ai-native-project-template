package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.model.AggregateRoot;
import com.aipersimmon.ddd.cqrs.AggregateCollector;
import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.cqrs.UnitOfWork;

/**
 * Runs the handler inside one {@link UnitOfWork} and, still within that
 * transaction, drains the domain events recorded by the aggregates the handler
 * touched — so the state change and its events commit or roll back together.
 *
 * <p>The handler (or its repositories) registers each touched aggregate with the
 * {@link AggregateCollector}; after the handler returns, this interceptor publishes
 * the recorded events of every {@link AbstractAggregateRoot} among them through the
 * {@link DomainEvents} port, clears them, and clears the collector. If no
 * {@code DomainEvents} publisher is configured, it still provides the transaction
 * boundary and simply skips the drain. Ordered innermost of the built-in chain so
 * the transaction wraps the handler but sits inside logging and validation.
 */
public class TransactionCommandInterceptor implements CommandInterceptor {

    /** Ordered innermost of the built-in interceptors. */
    public static final int ORDER = 200;

    private final UnitOfWork unitOfWork;
    private final AggregateCollector collector;
    private final DomainEvents domainEvents;

    /**
     * @param domainEvents the publisher to drain recorded events through, or
     *                     {@code null} to provide only the transaction boundary
     */
    public TransactionCommandInterceptor(UnitOfWork unitOfWork,
                                         AggregateCollector collector,
                                         DomainEvents domainEvents) {
        this.unitOfWork = unitOfWork;
        this.collector = collector;
        this.domainEvents = domainEvents;
    }

    @Override
    public <R> R intercept(Command<R> command, Invocation<R> invocation) {
        return unitOfWork.execute(() -> {
            collector.clear();
            try {
                R result = invocation.proceed();
                drainRecordedEvents();
                return result;
            } finally {
                collector.clear();
            }
        });
    }

    private void drainRecordedEvents() {
        if (domainEvents == null) {
            return;
        }
        for (AggregateRoot<?> aggregate : collector.collected()) {
            if (aggregate instanceof AbstractAggregateRoot<?> root) {
                domainEvents.publishAll(root.domainEvents());
                root.clearDomainEvents();
            }
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
