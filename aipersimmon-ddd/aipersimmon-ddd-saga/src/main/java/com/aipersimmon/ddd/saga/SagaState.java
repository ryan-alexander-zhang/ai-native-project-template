package com.aipersimmon.ddd.saga;

import java.util.Objects;

/**
 * Base class for the persisted state of a saga instance. It carries the two things
 * every saga needs — a correlation id that routes incoming events to the right
 * instance (for example the order id shared by every event in an order's flow) and
 * a lifecycle {@link SagaStatus} — and guards the legal status transitions.
 *
 * <p>A concrete saga extends this to add its own step and flow data, and its
 * event-reaction methods call {@link #startCompensation()}, {@link #complete()},
 * or {@link #abort()} as the flow progresses. Framework-free: it is plain state
 * with no persistence or scheduling concern of its own; a {@link SagaStore} loads
 * and saves it and a {@link DeadlineScheduler} handles its timeouts.
 */
public abstract class SagaState {

    private final String correlationId;
    private SagaStatus status;
    private final long version;

    /**
     * Constructor for a newly started saga (status {@code RUNNING}, version {@code 0}).
     *
     * @param correlationId the id shared by every event in this flow; must be
     *                      non-null and non-blank
     */
    protected SagaState(String correlationId) {
        this(correlationId, SagaStatus.RUNNING, 0L);
    }

    /**
     * Rehydrating constructor for a {@link SagaStore} loading a persisted instance.
     *
     * @param correlationId the id shared by every event in this flow
     * @param status        the persisted lifecycle status
     * @param version       the persisted optimistic-lock version
     */
    protected SagaState(String correlationId, SagaStatus status, long version) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.correlationId = correlationId;
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
    }

    public final String correlationId() {
        return correlationId;
    }

    public final SagaStatus status() {
        return status;
    }

    /**
     * The optimistic-lock version loaded with this instance: {@code 0} for a
     * not-yet-persisted saga, otherwise the version stored when it was loaded. A
     * {@link SagaStore} uses it to detect a concurrent modification on save.
     */
    public final long version() {
        return version;
    }

    /** Whether the saga is still advancing and should react to events. */
    public final boolean isActive() {
        return !status.isTerminal();
    }

    /** Enter compensation after a step failed. Only legal while {@code RUNNING}. */
    protected final void startCompensation() {
        requireStatus(SagaStatus.RUNNING);
        status = SagaStatus.COMPENSATING;
    }

    /** End the flow normally. Only legal while {@code RUNNING}. */
    protected final void complete() {
        requireStatus(SagaStatus.RUNNING);
        status = SagaStatus.COMPLETED;
    }

    /** End the flow after compensation (or an unrecoverable failure). */
    protected final void abort() {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "saga " + correlationId + " already ended as " + status);
        }
        status = SagaStatus.ABORTED;
    }

    private void requireStatus(SagaStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "saga " + correlationId + " is " + status + ", expected " + expected);
        }
    }
}
