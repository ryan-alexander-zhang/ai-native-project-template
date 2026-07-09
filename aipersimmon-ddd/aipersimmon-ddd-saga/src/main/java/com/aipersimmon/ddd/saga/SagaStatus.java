package com.aipersimmon.ddd.saga;

/**
 * The lifecycle status of a saga instance.
 *
 * <ul>
 *   <li>{@link #RUNNING} — started and advancing through its steps.</li>
 *   <li>{@link #COMPENSATING} — a step failed; running compensating actions to
 *       undo the work already done.</li>
 *   <li>{@link #COMPLETED} — every step succeeded; the flow ended normally.</li>
 *   <li>{@link #ABORTED} — the flow ended after compensation (or an
 *       unrecoverable failure).</li>
 * </ul>
 *
 * <p>{@code COMPLETED} and {@code ABORTED} are terminal.
 */
public enum SagaStatus {
    RUNNING,
    COMPENSATING,
    COMPLETED,
    ABORTED;

    /** Whether the saga has ended and should no longer react to events. */
    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED;
    }
}
