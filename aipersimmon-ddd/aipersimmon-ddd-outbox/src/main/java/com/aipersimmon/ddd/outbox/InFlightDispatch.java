package com.aipersimmon.ddd.outbox;

/**
 * A message the transport has been handed but has not yet confirmed. Returned by {@link
 * OutboxDispatcher#beginDispatch(OutboxMessage)} so the relay can hand over a whole claimed batch
 * before waiting on any of it: the wait then costs one round trip for the batch instead of one per
 * message, which is the difference between a backlog draining in minutes and in hours.
 *
 * <p>Splitting hand-over from confirmation is safe precisely because a claimed batch holds at most
 * one message per aggregate — the outbox claim admits only the head of each subject's queue — so no
 * two messages that must stay in order are ever in flight together.
 *
 * <p>{@link #awaitDelivery()} carries the same meaning the single-message {@link
 * OutboxDispatcher#dispatch} does: returning normally means delivered, and throwing leaves the row
 * unsent to be retried. An implementation must bound its own wait; the relay calls this on the one
 * thread that drains the outbox.
 */
@FunctionalInterface
public interface InFlightDispatch {

  /**
   * Block until the transport confirms delivery.
   *
   * @throws RuntimeException if delivery failed or was not confirmed in time
   */
  void awaitDelivery();

  /** Already delivered by the time it was handed over — nothing left to wait for. */
  InFlightDispatch CONFIRMED = () -> {};
}
