package com.aipersimmon.ddd.web.spi;

import java.time.Duration;
import java.util.Optional;

/**
 * Stores the first response for an idempotency key so a retried, authorised request
 * is executed once and subsequent replays return the original outcome. This is a
 * reliability concern (safe retries) — distinct from replay protection, which is a
 * security concern; see {@link ReplayGuard}.
 *
 * <p>Implementations must make {@link #saveIfAbsent} atomic (a single winner per
 * key) so concurrent first-time requests do not both execute.
 */
public interface IdempotencyStore {

    /** The stored response for {@code key}, if one has been recorded and not expired. */
    Optional<StoredResponse> find(String key);

    /**
     * Atomically records {@code response} for {@code key} only if none exists yet.
     *
     * @param key      the idempotency key
     * @param response the response to store
     * @param ttl      how long the entry should be retained
     * @return {@code true} if this call recorded the entry (the caller "won"),
     *         {@code false} if an entry already existed
     */
    boolean saveIfAbsent(String key, StoredResponse response, Duration ttl);
}
