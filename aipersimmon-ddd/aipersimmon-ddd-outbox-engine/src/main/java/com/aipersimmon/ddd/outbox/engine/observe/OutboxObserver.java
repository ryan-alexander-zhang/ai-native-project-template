package com.aipersimmon.ddd.outbox.engine.observe;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import java.time.Duration;

/**
 * A framework-free hook the relay calls to report what happened, so the engine carries no metrics
 * dependency of its own. The starter binds a Micrometer-backed implementation when a {@code
 * MeterRegistry} is present; otherwise {@link #NOOP} is used and an unwired deployment pays
 * nothing.
 *
 * <p>These are push signals — things that <em>happened</em>. How much is waiting is a level, not an
 * event, and is read on demand through {@link OutboxBacklog} instead; a counter cannot answer "how
 * deep is the backlog right now" and a gauge cannot answer "did we give up on anything in the last
 * hour".
 *
 * <p>Sits beside {@link com.aipersimmon.ddd.observability.StoreAndForwardTracer}, which carries the
 * other half of the same story: the tracer explains one message's journey, these meters describe
 * the population.
 */
public interface OutboxObserver {

  /** One poll's claim finished: {@code rows} were won, {@code latency} is the claim round-trip. */
  void claimed(int rows, Duration latency);

  /**
   * One row's dispatch finished. {@code success} means the transport accepted it — not that the
   * mark as sent succeeded, which is recorded separately by {@link #markSentFailed()} because a
   * broker that already has the message is not a delivery failure.
   */
  void dispatched(boolean success, Duration latency);

  /**
   * Delivery of a message was given up on and it was moved to the dead-letter table. The most
   * important signal here: an alert on this firing at all is how a lost message becomes known,
   * whereas the dead-letter table's depth only says how much is still waiting to be looked at.
   */
  void deadLettered(DeadLetterStore.Reason reason);

  /**
   * The transport accepted a message but recording that failed, so it will be delivered again. A
   * duplicate the consumer's inbox absorbs — worth watching, never worth alarming on alone.
   */
  void markSentFailed();

  /**
   * A poll handed {@code rows} back without dispatching them, having spent its time budget. Rare
   * and self-correcting, but a steady rate means the budget is too small for how slow dispatch has
   * become — the one signal that tells an operator to lower {@code batch-size} or raise the relay
   * lease.
   */
  void released(int rows);

  /** A no-op observer, so an unwired engine pays nothing. */
  OutboxObserver NOOP =
      new OutboxObserver() {
        @Override
        public void claimed(int rows, Duration latency) {}

        @Override
        public void dispatched(boolean success, Duration latency) {}

        @Override
        public void deadLettered(DeadLetterStore.Reason reason) {}

        @Override
        public void markSentFailed() {}

        @Override
        public void released(int rows) {}
      };
}
