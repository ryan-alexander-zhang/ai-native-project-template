package com.aipersimmon.ddd.processmanager.model;

/**
 * The optimistic-concurrency version of a process instance. It expresses only the
 * write generation used to admit exactly one transition at a time; it is not part of
 * the business state and never enters a state object. A fresh instance starts at
 * {@link #initial()} and advances by {@link #next()} on each committed transition.
 *
 * @param value the version, monotonically increasing; {@code >= 0}
 */
public record ProcessRevision(long value) {

    public ProcessRevision {
        if (value < 0) {
            throw new IllegalArgumentException("process revision must be >= 0");
        }
    }

    /** The revision of a not-yet-persisted instance. */
    public static ProcessRevision initial() {
        return new ProcessRevision(0);
    }

    /** The next revision after a committed transition. */
    public ProcessRevision next() {
        return new ProcessRevision(value + 1);
    }
}
