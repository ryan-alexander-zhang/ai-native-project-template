package com.aipersimmon.ddd.processmanager.engine.deadline;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineRow;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Fires due deadlines by turning each into an ordinary {@link ProcessInput} and re-entering {@link
 * ProcessRuntime#handle} — a deadline is not a separate callback. Firing and the {@code FIRED} mark
 * commit in one transaction, so a crash before commit is safely retried; the deadline's message id
 * is deterministic ({@code deadlineId#generation}), so the re-fire is a duplicate no-op in {@code
 * handle}.
 *
 * <p>Only active instances' deadlines are claimed (the claim strategy join), so a suspended or
 * ended instance's timers wait; a superseded generation is an auditable no-op. Exhausted retries
 * move the deadline to {@code DEAD} and suspend the instance.
 */
public final class ProcessDeadlineWorker {

  private final ProcessClaimStrategy claimStrategy;
  private final ProcessDeadlineStore deadlines;
  private final ProcessInstanceStore instances;
  private final ProcessPayloadCodecRegistry payloadCodecs;
  private final ProcessRuntime runtime;
  private final ProcessUnitOfWork unitOfWork;
  private final ProcessRetryPolicy retryPolicy;
  private final java.time.Clock clock;
  private final int batchSize;
  private final Duration leaseDuration;
  private final Supplier<String> leaseTokens;
  private final StoreAndForwardTracer storeTracer;

  public ProcessDeadlineWorker(
      ProcessClaimStrategy claimStrategy,
      ProcessDeadlineStore deadlines,
      ProcessInstanceStore instances,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessRuntime runtime,
      ProcessUnitOfWork unitOfWork,
      ProcessRetryPolicy retryPolicy,
      java.time.Clock clock,
      int batchSize,
      Duration leaseDuration,
      Supplier<String> leaseTokens) {
    this(
        claimStrategy,
        deadlines,
        instances,
        payloadCodecs,
        runtime,
        unitOfWork,
        retryPolicy,
        clock,
        batchSize,
        leaseDuration,
        leaseTokens,
        NoOpStoreAndForwardTracer.INSTANCE);
  }

  public ProcessDeadlineWorker(
      ProcessClaimStrategy claimStrategy,
      ProcessDeadlineStore deadlines,
      ProcessInstanceStore instances,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessRuntime runtime,
      ProcessUnitOfWork unitOfWork,
      ProcessRetryPolicy retryPolicy,
      java.time.Clock clock,
      int batchSize,
      Duration leaseDuration,
      Supplier<String> leaseTokens,
      StoreAndForwardTracer storeTracer) {
    this.claimStrategy = claimStrategy;
    this.deadlines = deadlines;
    this.instances = instances;
    this.payloadCodecs = payloadCodecs;
    this.runtime = runtime;
    this.unitOfWork = unitOfWork;
    this.retryPolicy = retryPolicy;
    this.clock = clock;
    this.batchSize = batchSize;
    this.leaseDuration = leaseDuration;
    this.leaseTokens = leaseTokens;
    this.storeTracer = storeTracer;
  }

  /** Claim and fire one batch of due deadlines; returns the number fired. */
  public int pollOnce() {
    String leaseToken = leaseTokens.get();
    Instant now = clock.instant();
    Instant leaseUntil = now.plus(leaseDuration);
    List<String> claimed =
        unitOfWork.execute(
            () -> claimStrategy.claimDueDeadlines(now, batchSize, leaseToken, leaseUntil));

    int fired = 0;
    for (String deadlineId : claimed) {
      if (fire(deadlineId, leaseToken)) {
        fired++;
      }
    }
    return fired;
  }

  private boolean fire(String deadlineId, String leaseToken) {
    Optional<DeadlineRow> loaded = deadlines.load(deadlineId);
    if (loaded.isEmpty()) {
      return false;
    }
    DeadlineRow deadline = loaded.get();
    try {
      // Everything runs in one fire transaction: locking the instance row first serializes any
      // concurrent CancelDeadline/reschedule advance, and re-reading the deadline status under its
      // own lock means a cancel or supersede that landed after the claim is observed here — so a
      // cancelled or superseded timer becomes an auditable no-op rather than firing anyway.
      return Boolean.TRUE.equals(
          unitOfWork.execute(
              () -> {
                Optional<ProcessInstanceRow> instance =
                    instances.findForUpdate(deadline.instanceId());
                if (instance.isEmpty()) {
                  deadlines.cancelClaimed(deadlineId, leaseToken, clock.instant());
                  return Boolean.FALSE;
                }
                Optional<DeadlineStatus> status = deadlines.statusForUpdate(deadlineId);
                if (status.isEmpty() || status.get() != DeadlineStatus.IN_FLIGHT) {
                  // Cancelled (or otherwise settled) between claim and fire: nothing to do.
                  return Boolean.FALSE;
                }
                if (deadline.generation()
                    != deadlines.currentGeneration(deadline.instanceId(), deadline.name())) {
                  deadlines.cancelClaimed(deadlineId, leaseToken, clock.instant());
                  return Boolean.FALSE;
                }
                ProcessPayloadCodec<?> codec = payloadCodecs.forType(deadline.inputType());
                ProcessInput input =
                    (ProcessInput)
                        codec.decode(
                            new EncodedPayload(deadline.inputType(), deadline.inputPayload()));
                // Fire under the correlation/causation persisted when the timer was scheduled, so
                // the
                // timeout stays on the same causal chain as the flow that armed it.
                CommandContext context =
                    new CommandContext(
                        deadline.deadlineId() + "#" + deadline.generation(),
                        deadline.correlationId(),
                        deadline.causationId());
                // Restore the scheduling advance's trace context so the timer's own advance links
                // back to the flow that armed it, rather than starting a fresh trace on the worker
                // thread.
                try (StoreAndForwardTracer.Scope span =
                    storeTracer.restore(
                        deadline.traceparent(),
                        deadline.traceState(),
                        "deadline.fire " + deadlineId)) {
                  try {
                    runtime.handle(instance.get().ref(), input, context);
                  } catch (RuntimeException e) {
                    span.recordFailure(e);
                    throw e;
                  }
                }
                deadlines.markFired(deadlineId, leaseToken, clock.instant());
                return Boolean.TRUE;
              }));
    } catch (RuntimeException failure) {
      onFailure(deadline, leaseToken, failure);
      return false;
    }
  }

  private void onFailure(DeadlineRow deadline, String leaseToken, RuntimeException failure) {
    String error = describe(failure);
    // attempts is bumped on failure (scheduleRetry/markDead), not on claim, so a lease-expiry
    // reclaim never consumes the retry budget. This failure is attempt N+1.
    int attempt = deadline.attempts() + 1;
    if (attempt >= retryPolicy.maxAttempts()) {
      unitOfWork.execute(
          () -> {
            int dead =
                deadlines.markDead(deadline.deadlineId(), leaseToken, error, clock.instant());
            if (dead == 1) {
              ProcessInstanceRow row = instances.findForUpdate(deadline.instanceId()).orElse(null);
              if (row != null && row.lifecycle().canSuspend()) {
                instances.suspend(
                    deadline.instanceId(),
                    row.lifecycle(),
                    "deadline " + deadline.deadlineId() + " exhausted retries",
                    "DEADLINE",
                    deadline.deadlineId(),
                    clock.instant());
              }
            }
            return null;
          });
    } else {
      Instant nextAttempt = clock.instant().plus(retryPolicy.backoff(attempt));
      deadlines.scheduleRetry(
          deadline.deadlineId(), leaseToken, nextAttempt, error, clock.instant());
    }
  }

  private static String describe(Throwable failure) {
    String message = failure.getClass().getName() + ": " + failure.getMessage();
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }
}
