package com.aipersimmon.ddd.outbox;

/**
 * Port the relay uses to deliver a stored outbox message to a broker. This core ships only a
 * logging default and an in-process republisher; a messaging starter supplies a real transport. A
 * dispatch that throws leaves the row unsent to be retried on the next poll.
 */
public interface OutboxDispatcher {

  void dispatch(OutboxMessage message);

  /**
   * Hand a message to the transport without waiting for it, returning the pending confirmation. The
   * relay hands over a whole claimed batch and only then waits on each, so a batch costs one broker
   * round trip rather than one per message — with a transport that acknowledges in tens of
   * milliseconds, that is the whole difference between draining a backlog in minutes and in hours.
   *
   * <p>Defaults to the synchronous {@link #dispatch}, so a transport that has nothing to overlap —
   * or was written before this existed — behaves exactly as it did: hand-over blocks until
   * delivered, and the confirmation is already complete. Only a transport with a genuinely
   * asynchronous send has anything to gain by overriding it.
   *
   * <p>Overlapping dispatches cannot reorder an aggregate's events, because a claimed batch holds
   * at most one message per aggregate: the outbox claim admits only the head of each subject's
   * queue, and the next one becomes claimable only once this one has been delivered.
   */
  default InFlightDispatch beginDispatch(OutboxMessage message) {
    dispatch(message);
    return InFlightDispatch.CONFIRMED;
  }

  /**
   * Whether this dispatcher can actually deliver an {@code @Externalized} event to its external
   * target. Answering {@code false} is an implementation's admission that it cannot — it is the one
   * fact the assembly cannot infer, because a dispatch that returns normally is indistinguishable
   * from a dispatch that delivered, and the relay marks the row sent either way. Without it, a
   * deployment that meant to publish externally but wired no transport archives every event as
   * delivered: no exception, no dead letter, no consumer lag — nothing to notice.
   *
   * <p>Defaults to {@code true} so a custom transport is trusted rather than accused: only the
   * dispatchers that knowingly stop at the process boundary ({@link LoggingOutboxDispatcher}, the
   * in-process republisher) override it. The outbox auto-configuration reads this at startup and
   * refuses to start when {@code @Externalized} events have nowhere to go.
   */
  default boolean reachesExternalTargets() {
    return true;
  }
}
