package com.aipersimmon.ddd.saga;

/**
 * Callback a saga implements to act on a {@link Deadline} that has come due. The
 * {@link DeadlineScheduler} invokes it when a deadline fires; the handler typically
 * loads the saga for {@link Deadline#correlationId()}, lets it react to the timeout
 * (advance, compensate, or end), and saves it back within one transaction. A
 * deadline that fires after its saga has already ended should be ignored.
 */
@FunctionalInterface
public interface DeadlineHandler {

    void onDeadline(Deadline deadline);
}
