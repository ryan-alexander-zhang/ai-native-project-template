package com.aipersimmon.ddd.cqrs;

import com.aipersimmon.ddd.core.model.AggregateRoot;
import java.util.Collection;

/**
 * Collects the aggregates touched while handling one command so their recorded
 * domain events can be drained and published in one place at the end of the unit
 * of work.
 *
 * <p>It exists because a plain JDBC/MyBatis persistence layer has no change
 * tracker to tell the application which aggregates changed: a repository (or the
 * handler) registers each aggregate it saves, and the transaction boundary reads
 * back {@link #collected()} to drain events, then {@link #clear()}s for the next
 * command. Implementations are scoped to a single command's execution.
 */
public interface AggregateCollector {

    /** Register an aggregate touched during the current command. */
    void register(AggregateRoot<?> aggregate);

    /** The aggregates registered so far during the current command. */
    Collection<AggregateRoot<?>> collected();

    /** Forget all registered aggregates, readying the collector for the next command. */
    void clear();
}
