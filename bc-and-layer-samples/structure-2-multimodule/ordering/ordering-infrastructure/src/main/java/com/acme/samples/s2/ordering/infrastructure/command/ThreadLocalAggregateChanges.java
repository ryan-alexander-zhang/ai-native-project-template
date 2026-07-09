package com.acme.samples.s2.ordering.infrastructure.command;

import com.acme.samples.s2.shared.AggregateChanges;
import com.acme.samples.s2.shared.AggregateRoot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-bound implementation of {@link AggregateChanges}. A command runs on one
 * thread (web request or Kafka-listener thread), so a {@link ThreadLocal} scopes
 * the collected aggregates to the in-flight command. The Transaction decorator
 * calls {@link #drain()} to publish their events and {@link #clear()} to release
 * the thread-local afterwards.
 */
@Component
public class ThreadLocalAggregateChanges implements AggregateChanges {

    private final ThreadLocal<List<AggregateRoot>> holder = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public void register(AggregateRoot aggregate) {
        holder.get().add(aggregate);
    }

    /** Return the registered aggregates and empty the buffer (still same thread/tx). */
    public List<AggregateRoot> drain() {
        List<AggregateRoot> collected = new ArrayList<>(holder.get());
        holder.get().clear();
        return collected;
    }

    /** Release the thread-local; call in a finally after each command. */
    public void clear() {
        holder.remove();
    }
}
