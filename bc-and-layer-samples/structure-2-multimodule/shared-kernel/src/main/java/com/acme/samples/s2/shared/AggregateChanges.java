package com.acme.samples.s2.shared;

/**
 * Per-request collector of aggregates touched by a command (analysis-00005 §5.1).
 * Command handlers {@link #register(AggregateRoot)} each aggregate they mutate; the
 * Transaction (UnitOfWork) decorator drains them after the handler returns and
 * publishes their domain events inside the same transaction. This substitutes for
 * an EF-style ChangeTracker, which MyBatis does not provide. Framework-free port.
 */
public interface AggregateChanges {
    void register(AggregateRoot aggregate);
}
