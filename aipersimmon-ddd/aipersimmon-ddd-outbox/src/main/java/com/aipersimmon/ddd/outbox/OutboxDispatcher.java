package com.aipersimmon.ddd.outbox;

/**
 * Port the relay uses to deliver a stored outbox message to a broker. This core ships only a
 * logging default and an in-process republisher; a messaging starter supplies a real transport. A
 * dispatch that throws leaves the row unsent to be retried on the next poll.
 */
public interface OutboxDispatcher {

  void dispatch(OutboxMessage message);

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
