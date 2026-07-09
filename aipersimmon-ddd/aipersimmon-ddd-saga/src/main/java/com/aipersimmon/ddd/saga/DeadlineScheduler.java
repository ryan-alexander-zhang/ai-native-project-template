package com.aipersimmon.ddd.saga;

/**
 * Port for registering and cancelling saga {@link Deadline}s. A saga schedules a
 * deadline when it starts waiting for something that may never arrive, and cancels
 * it once the awaited event does arrive. When a deadline comes due the scheduler
 * dispatches it to the registered {@link DeadlineHandler}. Keeping this a port lets
 * the timeout mechanism be swapped — an in-process scheduler, a database poll, or a
 * durable-execution engine's timer — without changing the saga.
 */
public interface DeadlineScheduler {

    /** Register a deadline to fire at {@link Deadline#fireAt()}. */
    void schedule(Deadline deadline);

    /**
     * Cancel a previously scheduled deadline by its correlation id and name. A no-op
     * if none is pending (for example, it already fired).
     */
    void cancel(String correlationId, String name);
}
