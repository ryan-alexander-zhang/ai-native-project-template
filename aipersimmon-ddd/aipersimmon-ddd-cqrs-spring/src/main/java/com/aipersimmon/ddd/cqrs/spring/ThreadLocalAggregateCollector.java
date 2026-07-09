package com.aipersimmon.ddd.cqrs.spring;

import com.aipersimmon.ddd.core.model.AggregateRoot;
import com.aipersimmon.ddd.cqrs.AggregateCollector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Thread-scoped {@link AggregateCollector}: it gathers the aggregates registered
 * on the current thread while a command is handled, so the transaction interceptor
 * — running the handler synchronously on that same thread — can drain their events
 * afterwards. It works outside a web request too, since it binds to the thread
 * rather than to an HTTP scope. The interceptor clears it around each command, so
 * one command never sees another's aggregates.
 */
public class ThreadLocalAggregateCollector implements AggregateCollector {

    private final ThreadLocal<List<AggregateRoot<?>>> current =
            ThreadLocal.withInitial(ArrayList::new);

    @Override
    public void register(AggregateRoot<?> aggregate) {
        if (aggregate != null) {
            current.get().add(aggregate);
        }
    }

    @Override
    public Collection<AggregateRoot<?>> collected() {
        return List.copyOf(current.get());
    }

    @Override
    public void clear() {
        current.remove();
    }
}
