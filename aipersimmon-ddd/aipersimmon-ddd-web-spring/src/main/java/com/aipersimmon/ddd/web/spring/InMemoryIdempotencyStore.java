package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyStore} for single-node and development use. Entries
 * expire lazily on read. Not suitable for multiple instances — use a store backend
 * module ({@code -web-store-redis}/{@code -web-store-jdbc}) in production, which
 * replaces this bean.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(StoredResponse response, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(clock.instant())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    @Override
    public boolean saveIfAbsent(String key, StoredResponse response, Duration ttl) {
        Instant expiresAt = clock.instant().plus(ttl);
        Entry existing = entries.compute(key, (k, current) -> {
            if (current != null && current.expiresAt().isAfter(clock.instant())) {
                return current;
            }
            return new Entry(response, expiresAt);
        });
        return existing.response() == response;
    }
}
