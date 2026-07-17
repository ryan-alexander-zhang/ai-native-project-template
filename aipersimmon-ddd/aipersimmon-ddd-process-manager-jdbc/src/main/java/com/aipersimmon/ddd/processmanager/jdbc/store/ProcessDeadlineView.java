package com.aipersimmon.ddd.processmanager.jdbc.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Operator-facing summary of a scheduled deadline (design-00004 §4.10) — its identity, name,
 * generation, due time, status, attempt count, retry schedule, and last error — for a
 * pending/dead worklist. The operator addresses the row by {@code deadlineId} and {@code generation}
 * to redrive it.
 */
public record ProcessDeadlineView(
        String deadlineId,
        String instanceId,
        String name,
        long generation,
        String status,
        Instant dueAt,
        int attempts,
        Optional<Instant> nextAttemptAt,
        Optional<String> lastError) {
}
