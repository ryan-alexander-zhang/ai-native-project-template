package com.aipersimmon.ddd.processmanager.jdbc.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Operator-facing summary of a staged effect (design-00004 §4.10) — its identity, kind, delivery
 * status, attempt count, retry schedule, and last error — for a pending/dead worklist. The encoded
 * payload is omitted; the operator addresses the row by {@code effectId} to redrive it.
 */
public record ProcessEffectView(
        String effectId,
        String instanceId,
        String kind,
        String status,
        int attempts,
        String messageId,
        Optional<Instant> nextAttemptAt,
        Optional<String> lastError,
        Instant createdAt) {
}
