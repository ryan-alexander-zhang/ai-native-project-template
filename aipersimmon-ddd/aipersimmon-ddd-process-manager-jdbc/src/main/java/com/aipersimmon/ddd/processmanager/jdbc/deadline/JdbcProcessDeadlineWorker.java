package com.aipersimmon.ddd.processmanager.jdbc.deadline;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.jdbc.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore.DeadlineRow;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fires due deadlines by turning each into an ordinary {@link ProcessInput} and
 * re-entering {@link ProcessRuntime#handle} — a deadline is not a separate callback.
 * Firing and the {@code FIRED} mark commit in one transaction, so a
 * crash before commit is safely retried; the deadline's message id is deterministic
 * ({@code deadlineId#generation}), so the re-fire is a duplicate no-op in {@code handle}.
 *
 * <p>Only active instances' deadlines are claimed (the dialect join), so a suspended or
 * ended instance's timers wait; a superseded generation is an auditable no-op. Exhausted
 * retries move the deadline to {@code DEAD} and suspend the instance.
 */
public final class JdbcProcessDeadlineWorker {

    private final JdbcTemplate jdbc;
    private final JdbcProcessDialect dialect;
    private final JdbcProcessDeadlineStore deadlines;
    private final JdbcProcessInstanceStore instances;
    private final ProcessPayloadCodecRegistry payloadCodecs;
    private final ProcessRuntime runtime;
    private final JdbcProcessUnitOfWork unitOfWork;
    private final ProcessRetryPolicy retryPolicy;
    private final java.time.Clock clock;
    private final WorkerId workerId;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Supplier<String> leaseTokens;

    public JdbcProcessDeadlineWorker(
            JdbcTemplate jdbc,
            JdbcProcessDialect dialect,
            JdbcProcessDeadlineStore deadlines,
            JdbcProcessInstanceStore instances,
            ProcessPayloadCodecRegistry payloadCodecs,
            ProcessRuntime runtime,
            JdbcProcessUnitOfWork unitOfWork,
            ProcessRetryPolicy retryPolicy,
            java.time.Clock clock,
            WorkerId workerId,
            int batchSize,
            Duration leaseDuration,
            Supplier<String> leaseTokens) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.deadlines = deadlines;
        this.instances = instances;
        this.payloadCodecs = payloadCodecs;
        this.runtime = runtime;
        this.unitOfWork = unitOfWork;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.workerId = workerId;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.leaseTokens = leaseTokens;
    }

    /** Claim and fire one batch of due deadlines; returns the number fired. */
    public int pollOnce() {
        String leaseToken = leaseTokens.get();
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(leaseDuration);
        List<String> claimed = unitOfWork.execute(
                () -> dialect.claimDueDeadlines(jdbc, now, batchSize, workerId, leaseToken, leaseUntil));

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
            return Boolean.TRUE.equals(unitOfWork.execute(() -> {
                Optional<ProcessInstanceRow> instance = instances.findForUpdate(deadline.instanceId());
                if (instance.isEmpty()) {
                    deadlines.cancelClaimed(deadlineId, leaseToken, clock.instant());
                    return Boolean.FALSE;
                }
                Optional<JdbcProcessDeadlineStore.DeadlineStatus> status = deadlines.statusForUpdate(deadlineId);
                if (status.isEmpty() || status.get() != JdbcProcessDeadlineStore.DeadlineStatus.IN_FLIGHT) {
                    // Cancelled (or otherwise settled) between claim and fire: nothing to do.
                    return Boolean.FALSE;
                }
                if (deadline.generation() != deadlines.currentGeneration(deadline.instanceId(), deadline.name())) {
                    deadlines.cancelClaimed(deadlineId, leaseToken, clock.instant());
                    return Boolean.FALSE;
                }
                ProcessPayloadCodec<?> codec = payloadCodecs.forType(deadline.inputType());
                ProcessInput input = (ProcessInput) codec.decode(
                        new EncodedPayload(deadline.inputType(), deadline.inputPayload()));
                // Fire under the correlation/causation/trace persisted when the timer was scheduled, so the
                // timeout stays on the same causal chain as the flow that armed it.
                CommandContext context = new CommandContext(
                        deadline.deadlineId() + "#" + deadline.generation(),
                        deadline.correlationId(), deadline.causationId(), deadline.traceId());
                runtime.handle(instance.get().ref(), input, context);
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
            unitOfWork.execute(() -> {
                int dead = deadlines.markDead(deadline.deadlineId(), leaseToken, error, clock.instant());
                if (dead == 1) {
                    ProcessInstanceRow row = instances.findForUpdate(deadline.instanceId()).orElse(null);
                    if (row != null && row.lifecycle().canSuspend()) {
                        instances.suspend(
                                deadline.instanceId(), row.lifecycle(),
                                "deadline " + deadline.deadlineId() + " exhausted retries", "DEADLINE",
                                deadline.deadlineId(), clock.instant());
                    }
                }
                return null;
            });
        } else {
            Instant nextAttempt = clock.instant().plus(retryPolicy.backoff(attempt));
            deadlines.scheduleRetry(deadline.deadlineId(), leaseToken, nextAttempt, error, clock.instant());
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getClass().getName() + ": " + failure.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
