package com.aipersimmon.ddd.processmanager.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The runtime lifecycle of a process instance — distinct from any aggregate status
 * and from the consumer's business {@link ProcessStep}. It expresses only how the
 * durable runtime sees the instance: running, compensating, suspended for operator
 * attention, or in one of three terminal states.
 *
 * <p>{@link #SUSPENDED} is an operational state the runtime sets when delivery or a
 * deadline exhausts its retries; a {@code ProcessDefinition} must never return it (a
 * business wait such as human review stays {@code RUNNING} with a business step). The
 * legal transitions are defined below; the runtime validates every transition against
 * {@link #canTransitionTo(ProcessLifecycle)}.
 */
public enum ProcessLifecycle {

    RUNNING,
    COMPENSATING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Map<ProcessLifecycle, Set<ProcessLifecycle>> LEGAL = Map.of(
            RUNNING, EnumSet.of(RUNNING, COMPENSATING, SUSPENDED, COMPLETED, FAILED, CANCELLED),
            COMPENSATING, EnumSet.of(COMPENSATING, SUSPENDED, COMPLETED, FAILED, CANCELLED),
            SUSPENDED, EnumSet.of(RUNNING, COMPENSATING, CANCELLED),
            COMPLETED, EnumSet.noneOf(ProcessLifecycle.class),
            FAILED, EnumSet.noneOf(ProcessLifecycle.class),
            CANCELLED, EnumSet.noneOf(ProcessLifecycle.class));

    /** {@code true} for {@link #COMPLETED}, {@link #FAILED}, {@link #CANCELLED}. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** {@code true} while the instance can still advance (i.e. not terminal). */
    public boolean isActive() {
        return !isTerminal();
    }

    /**
     * Whether a direct transition from this lifecycle to {@code target} is legal.
     * A terminal state permits no transition, so ordinary input
     * to a finished instance can only be an idempotent no-op.
     */
    public boolean canTransitionTo(ProcessLifecycle target) {
        return LEGAL.get(this).contains(target);
    }
}
