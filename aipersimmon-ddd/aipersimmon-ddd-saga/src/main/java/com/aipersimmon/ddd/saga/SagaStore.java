package com.aipersimmon.ddd.saga;

import java.util.Optional;

/**
 * Port for persisting and loading saga instances by correlation id. An incoming
 * event is routed by looking up the instance for its correlation id, letting the
 * saga react, then saving it back — all in one transaction. Implementations are
 * expected to guard concurrent advances of the same instance with optimistic
 * locking so two events arriving at once cannot both win.
 *
 * @param <S> the concrete saga state type this store persists
 */
public interface SagaStore<S extends SagaState> {

    /** Load the saga for a correlation id, or empty if none has started. */
    Optional<S> find(String correlationId);

    /**
     * Insert a newly started saga or update an existing one. Implementations detect
     * a concurrent modification via optimistic locking and signal it to the caller.
     */
    void save(S saga);
}
