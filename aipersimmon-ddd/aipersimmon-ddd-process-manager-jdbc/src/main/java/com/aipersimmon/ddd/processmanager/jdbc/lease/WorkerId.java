package com.aipersimmon.ddd.processmanager.jdbc.lease;

import java.util.UUID;

/**
 * The lease identity of a worker node — used only to own a claim, never a business
 * identity. Set explicitly for a deployment, or generated per process when unset.
 *
 * @param value the worker id; non-blank
 */
public record WorkerId(String value) {

    public WorkerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("worker id required");
        }
    }

    /** A process-scoped random worker id, for when none is configured. */
    public static WorkerId generate() {
        return new WorkerId("worker-" + UUID.randomUUID());
    }
}
