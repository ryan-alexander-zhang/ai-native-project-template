package com.aipersimmon.ddd.outbox.engine.relay;

import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.InFlightDispatch;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.outbox.UnreachableDestinationException;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claims unsent rows that are due and dispatches them, recording those the transport confirmed —
 * at-least-once delivery.
 *
 * <p>A batch is handed to the transport <em>before</em> any of it is waited on, and the confirmed
 * rows are then recorded in one write. Waiting on each send in turn made a poll cost the sum of its
 * round trips — with a broker acknowledging in tens of milliseconds, an instance managed on the
 * order of a hundred messages a second, so an hour's backlog took the better part of a day to
 * drain. Overlapping them makes a poll cost roughly one round trip regardless of batch size.
 * Nothing about the guarantees changes: a row is recorded sent only once its own delivery is
 * confirmed, and a failure is still that row's alone. Ordering is safe because a claimed batch
 * holds at most one row per aggregate (see below), so two events that must stay in order are never
 * in flight together.
 *
 * <p>Rows are claimed, not merely selected. Each claim stamps a lease on the rows it wins, so every
 * instance can poll at the same time and they simply take disjoint work. That is what keeps a lost
 * instance from stopping delivery: it cannot release anything when it is killed, but its rows come
 * back on their own once the lease expires, and in the meantime every other instance keeps
 * dispatching. Nothing has to detect the death.
 *
 * <p>Per-aggregate ordering is a property of the claim, not of this loop: only the head of each
 * {@code subject}'s live queue is claimable, so an aggregate has at most one row in flight anywhere
 * and a later event cannot overtake an earlier one however many instances are polling. See {@link
 * OutboxStore#claimDue}. This class therefore does not track blocked aggregates itself — a
 * guarantee that lived in one node's memory could not have survived concurrent pollers.
 *
 * <p>A failed dispatch is classified by the {@link FailureClassifier}. A <em>permanent</em> failure
 * is dead-lettered at once (no retries wasted). A <em>transient</em> failure is retried with
 * exponential backoff: its next attempt is pushed out (see {@link RetryBackoff}) so the claim skips
 * it until then, rather than re-attempting it every second. When a transient failure has burned
 * through {@code max-attempts} it too is dead-lettered. In every give-up case the row is
 * <em>moved</em> to the {@link DeadLetterStore} (out of the outbox), so the hot table holds only
 * live work and a spent message is preserved for inspection and replay rather than lost. Because a
 * dead-lettered row leaves the table, its aggregate's later events then proceed — ordering is
 * preserved only up to the point a message is given up on.
 *
 * <p>One poll is bounded twice: it dispatches at most {@code batch-size} rows, and it stops handing
 * rows over once half the lease has elapsed, releasing whatever it had claimed but not reached.
 * Bounding it here is what lets the lease be short: a poll cannot outlive the lease it holds as
 * long as a single dispatch takes less than half of one, so the length of the lease is free to be
 * chosen for how fast a dead instance's rows should come back rather than for how slow a whole
 * batch of stalled sends might be. That the budget covers only <em>one</em> dispatch and not a
 * whole batch of them is exactly what overlapping the sends buys: a batch that all stalls costs one
 * timeout, not one per row.
 *
 * <p>Because only the head of an aggregate is claimable, one claim yields at most one row per
 * aggregate. A poll therefore claims repeatedly — as each row is sent or given up on, its successor
 * becomes the head — until the batch is spent, nothing is claimable, or a row is left in the table
 * still immediately claimable. That last condition is what keeps a retry scheduled with little or
 * no backoff from being re-attempted straight away, spending its whole attempt budget in one tight
 * loop. A dead-lettered row is not such a case, having left the table altogether, so giving up on a
 * message still frees its aggregate's next event within the same poll.
 *
 * <p>Where a row goes was decided when it was written, not here: the destination is a column. This
 * loop only enforces the one invariant that follows from that — a row naming an external
 * destination is never handed to a dispatcher that has admitted it cannot reach one, because
 * delivering it locally would mark it sent while it never left the process. Which external target
 * it goes to is the transport dispatcher's business.
 *
 * <p>Everything it does is reported through an {@link OutboxObserver} — claim and dispatch latency,
 * what was given up on, what was handed back — so an operator can see the population rather than
 * inferring it from logs. How much is <em>waiting</em> is a level rather than an event and is read
 * separately, through {@code OutboxBacklog}.
 *
 * <p>This class is storage-agnostic on purpose. Every judgement above — the classification, the
 * retry budget, what a mark-sent failure means, how a dead-letter move that itself fails is
 * handled, the poll budget — is reasoning that must not differ between backends, so it lives here
 * once and runs against {@link OutboxStore}.
 */
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxStore store;
  private final OutboxDispatcher dispatcher;
  private final DeadLetterStore deadLetterStore;
  private final FailureClassifier failureClassifier;
  private final RetryBackoff backoff;
  private final Clock clock;
  private final int batchSize;
  private final int maxAttempts;
  private final RelayLeases leases;
  private final StoreAndForwardTracer tracer;
  private final OutboxObserver observer;

  public OutboxRelay(
      OutboxStore store,
      OutboxDispatcher dispatcher,
      DeadLetterStore deadLetterStore,
      FailureClassifier failureClassifier,
      RetryBackoff backoff,
      Clock clock,
      int batchSize,
      int maxAttempts,
      RelayLeases leases) {
    this(
        store,
        dispatcher,
        deadLetterStore,
        failureClassifier,
        backoff,
        clock,
        batchSize,
        maxAttempts,
        leases,
        NoOpStoreAndForwardTracer.INSTANCE,
        OutboxObserver.NOOP);
  }

  public OutboxRelay(
      OutboxStore store,
      OutboxDispatcher dispatcher,
      DeadLetterStore deadLetterStore,
      FailureClassifier failureClassifier,
      RetryBackoff backoff,
      Clock clock,
      int batchSize,
      int maxAttempts,
      RelayLeases leases,
      StoreAndForwardTracer tracer) {
    this(
        store,
        dispatcher,
        deadLetterStore,
        failureClassifier,
        backoff,
        clock,
        batchSize,
        maxAttempts,
        leases,
        tracer,
        OutboxObserver.NOOP);
  }

  public OutboxRelay(
      OutboxStore store,
      OutboxDispatcher dispatcher,
      DeadLetterStore deadLetterStore,
      FailureClassifier failureClassifier,
      RetryBackoff backoff,
      Clock clock,
      int batchSize,
      int maxAttempts,
      RelayLeases leases,
      StoreAndForwardTracer tracer,
      OutboxObserver observer) {
    this.store = store;
    this.dispatcher = dispatcher;
    this.deadLetterStore = deadLetterStore;
    this.failureClassifier = failureClassifier;
    this.backoff = backoff;
    this.clock = clock;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.leases = leases;
    this.tracer = tracer;
    this.observer = observer;
  }

  /**
   * Drain up to one batch of claimable rows. Scheduling lives in {@link OutboxRelayScheduler}; no
   * lock guards this, so a direct call always runs — the claim is what keeps concurrent callers off
   * each other's rows.
   */
  public void relay() {
    Instant budgetEndsAt = clock.instant().plus(pollBudget());
    int remaining = batchSize;
    while (remaining > 0 && !clock.instant().isAfter(budgetEndsAt)) {
      Instant now = clock.instant();
      long claimStart = System.nanoTime();
      List<PendingMessage> claimed = store.claimDue(now, maxAttempts, remaining, leases.next(now));
      observer.claimed(claimed.size(), since(claimStart));
      if (claimed.isEmpty()) {
        return;
      }
      remaining -= claimed.size();
      if (!dispatchAll(claimed, budgetEndsAt)) {
        return;
      }
    }
  }

  /** What became of a row, from the point of view of whether this poll should claim again. */
  private enum Outcome {
    /** Delivered and recorded. Its aggregate's next event is now the head. */
    SENT,
    /** Given up on and moved out of the outbox. Its aggregate is free of it too. */
    RETIRED,
    /**
     * Still in the table and claimable again at once — a scheduled retry with little or no backoff,
     * or a row handed back. Claiming again in this same poll would re-attempt it immediately, so
     * the poll ends instead and the schedule decides when to look again.
     */
    HELD
  }

  /**
   * Hand every claimed row to the transport, then wait on them all — stopping the hand-over if the
   * poll's time budget runs out and handing back what is left so it does not sit unavailable for
   * the rest of its lease.
   *
   * @return {@code true} if claiming again is worthwhile — no row was left immediately re-claimable
   */
  private boolean dispatchAll(List<PendingMessage> claimed, Instant budgetEndsAt) {
    boolean keepClaiming = true;
    List<InFlight> inFlight = new ArrayList<>(claimed.size());
    int handedOver = 0;
    while (handedOver < claimed.size() && !clock.instant().isAfter(budgetEndsAt)) {
      PendingMessage pending = claimed.get(handedOver++);
      OutboxMessage message = pending.message();
      if (message.destination() != null && !dispatcher.reachesExternalTargets()) {
        // The row names an external destination, decided when it was written, and the active
        // dispatcher has admitted it cannot reach one. Delivering it locally would archive it as
        // sent while it never left the process — the loss that storing the destination exists to
        // end. Fail instead, so it retries and then becomes a visible dead letter.
        if (handleFailure(
                pending,
                new UnreachableDestinationException(
                    message.type(), message.version(), message.destination()))
            == Outcome.HELD) {
          keepClaiming = false;
        }
        continue;
      }
      inFlight.add(handOver(pending));
    }
    if (handedOver < claimed.size()) {
      List<String> untouched =
          claimed.subList(handedOver, claimed.size()).stream()
              .map(p -> p.message().eventId())
              .toList();
      log.warn(
          "outbox poll spent its time budget of {} with {} claimed row(s) not yet dispatched; "
              + "releasing them for the next poll (or another instance). Lower batch-size, speed "
              + "up dispatch, or raise the relay lease if this recurs",
          pollBudget(),
          untouched.size());
      observer.released(untouched.size());
      release(untouched);
      keepClaiming = false;
    }
    // Everything handed over is waited on, budget or not: abandoning a send already with the
    // transport would either strand the row for its whole lease or duplicate a message that was
    // in fact delivered. The wait is bounded once for the batch, not once per message, because
    // the sends overlap — which is why the poll budget still only has to leave room for one.
    boolean recorded = confirmAll(inFlight);
    return keepClaiming && recorded;
  }

  /**
   * Hand one row to the transport without waiting for it. The restored span is current only across
   * the hand-over — that is where the producer instrumentation reads it to stamp the message's
   * trace headers — and is then detached, because the next row is handed over on this same thread
   * while this one is still in flight. The span itself stays open until delivery is confirmed, so a
   * failed acknowledgement is recorded on the span that sent it.
   */
  private InFlight handOver(PendingMessage pending) {
    OutboxMessage message = pending.message();
    long start = System.nanoTime();
    StoreAndForwardTracer.Scope span =
        tracer.restore(
            pending.traceparent(), pending.traceState(), "outbox.publish " + message.eventId());
    try {
      InFlightDispatch handle = dispatcher.beginDispatch(message);
      span.detach();
      return new InFlight(pending, handle, span, start);
    } catch (RuntimeException e) {
      // It failed before the transport even took it. Carry it in the same shape as everything
      // else so one place decides what a failure means.
      span.detach();
      return new InFlight(pending, rethrowing(e), span, start);
    }
  }

  /**
   * Wait on every handed-over row, then record the delivered ones in a single write.
   *
   * @return {@code true} if claiming again is worthwhile
   */
  private boolean confirmAll(List<InFlight> inFlight) {
    boolean keepClaiming = true;
    List<String> delivered = new ArrayList<>(inFlight.size());
    for (InFlight one : inFlight) {
      RuntimeException failure = confirm(one);
      if (failure == null) {
        delivered.add(one.pending().message().eventId());
      } else if (handleFailure(one.pending(), failure) == Outcome.HELD) {
        keepClaiming = false;
      }
    }
    boolean recorded = recordDelivered(delivered);
    return keepClaiming && recorded;
  }

  /** Waits for one delivery. Returns the failure, or {@code null} if it was delivered. */
  private RuntimeException confirm(InFlight one) {
    RuntimeException failure = null;
    try (StoreAndForwardTracer.Scope span = one.span()) {
      try {
        one.handle().awaitDelivery();
      } catch (RuntimeException e) {
        span.recordFailure(e);
        failure = e;
      }
    }
    observer.dispatched(failure == null, since(one.start()));
    return failure;
  }

  /**
   * Records a confirmed batch as sent, in one write.
   *
   * <p>The messages are delivered (at-least-once satisfied). A failure to record that is NOT a
   * dispatch failure: never dead-letter or count it against the retry budget — that would discard
   * or misreport messages the broker already has. Release the rows instead, so the next poll
   * re-dispatches them (accepted at-least-once duplicates, which the consumer's inbox dedups)
   * rather than waiting out the lease.
   *
   * @return {@code true} if the batch was recorded
   */
  private boolean recordDelivered(List<String> eventIds) {
    if (eventIds.isEmpty()) {
      return true;
    }
    try {
      store.markSent(eventIds, clock.instant());
      return true;
    } catch (RuntimeException e) {
      log.warn(
          "outbox dispatch of {} row(s) succeeded but marking them sent failed; "
              + "they will be re-dispatched (duplicates) on the next poll",
          eventIds.size(),
          e);
      observer.markSentFailed(eventIds.size());
      release(eventIds);
      return false;
    }
  }

  /** A row the transport has taken and not yet confirmed. */
  private record InFlight(
      PendingMessage pending,
      InFlightDispatch handle,
      StoreAndForwardTracer.Scope span,
      long start) {}

  private static InFlightDispatch rethrowing(RuntimeException error) {
    return () -> {
      throw error;
    };
  }

  /** Handles a failed dispatch: dead-letter it, or schedule the next attempt. */
  private Outcome handleFailure(PendingMessage pending, RuntimeException error) {
    int attempts = pending.attempts() + 1;
    if (failureClassifier.classify(error) == FailureClassifier.Failure.PERMANENT) {
      return deadLetter(
          pending,
          attempts,
          DeadLetterStore.Reason.PERMANENT,
          error,
          "failed permanently; dead-lettered without retry");
    }
    if (attempts >= maxAttempts) {
      return deadLetter(
          pending,
          attempts,
          DeadLetterStore.Reason.RETRIES_EXHAUSTED,
          error,
          "failed " + maxAttempts + " times; dead-lettered");
    }
    Duration delay = backoff.nextDelay(attempts);
    store.scheduleRetry(pending.message().eventId(), clock.instant().plus(delay));
    log.warn(
        "outbox dispatch failed for eventId={}, retrying in {}ms (attempt {}/{})",
        pending.message().eventId(),
        delay.toMillis(),
        attempts,
        maxAttempts,
        error);
    return Outcome.HELD;
  }

  /**
   * Moves a given-up row to the {@link DeadLetterStore} (out of the outbox). If that move fails —
   * the store is unavailable — it does not let the failure propagate and abort the poll, nor leave
   * the row claimable at once (which would have the poll re-select and re-dispatch it every
   * second). Instead it backs the row off without counting an attempt, so the move is retried at
   * the backoff cadence and self-heals once the store recovers.
   *
   * @return {@link Outcome#RETIRED} once the row is out of the table — its aggregate is unblocked
   *     and this poll may go on — or {@link Outcome#HELD} if the move failed and the row stayed put
   */
  private Outcome deadLetter(
      PendingMessage pending,
      int attempts,
      DeadLetterStore.Reason reason,
      RuntimeException error,
      String givingUp) {
    OutboxMessage message = pending.message();
    try {
      deadLetterStore.store(
          message, attempts, reason, summarize(error), pending.traceparent(), pending.traceState());
      log.error("outbox dispatch for eventId={} {}", message.eventId(), givingUp, error);
      observer.deadLettered(reason);
      return Outcome.RETIRED;
    } catch (RuntimeException storeError) {
      storeError.addSuppressed(error);
      Duration delay = backoff.nextDelay(attempts);
      store.backOffWithoutAttempt(message.eventId(), clock.instant().plus(delay));
      log.error(
          "outbox dead-letter move for eventId={} failed; backing off {}ms so it is not "
              + "re-dispatched every poll (retried until the dead-letter store recovers)",
          message.eventId(),
          delay.toMillis(),
          storeError);
      return Outcome.HELD;
    }
  }

  /**
   * Hands rows back so they are claimable again immediately. A failure here is not worth failing
   * the poll over: the lease expiring achieves the same thing, only later.
   */
  private void release(List<String> eventIds) {
    try {
      store.release(eventIds);
    } catch (RuntimeException e) {
      log.warn(
          "outbox could not release {} claimed row(s); they become claimable again when their "
              + "lease expires",
          eventIds.size(),
          e);
    }
  }

  /**
   * How long one poll may keep working. Half the lease, so the remaining half is slack for the
   * dispatch already in flight when the budget runs out — a poll cannot outlive its own lease as
   * long as a single dispatch is shorter than this.
   */
  private Duration pollBudget() {
    return leases.duration().dividedBy(2);
  }

  private static Duration since(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }

  private static String summarize(Throwable error) {
    return error.getClass().getName() + ": " + error.getMessage();
  }
}
