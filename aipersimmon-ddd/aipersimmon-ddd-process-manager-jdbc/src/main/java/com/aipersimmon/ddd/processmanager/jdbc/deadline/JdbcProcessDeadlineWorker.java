package com.aipersimmon.ddd.processmanager.jdbc.deadline;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.exception.ProcessSuspendedException;
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
 * re-entering {@link ProcessRuntime#handle} — a deadline is not a separate callback
 * (design-00004 §4.7). Firing and the {@code FIRED} mark commit in one transaction, so a
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
        Optional<ProcessInstanceRow> instance = instances.find(deadline.instanceId());
        if (instance.isEmpty()) {
            deadlines.cancelClaimed(deadlineId, leaseToken, clock.instant());
            return false;
        }
        if (deadline.generation() != deadlines.currentGeneration(deadline.instanceId(), deadline.name())) {
            // A later reschedule superseded this generation: an auditable no-op.
            deadlines.cancelClaimed(deadlineId, leaseToken, clock.instant());
            return false;
        }

        try {
            unitOfWork.execute(() -> {
                ProcessPayloadCodec<?> codec = payloadCodecs.forType(deadline.inputType());
                ProcessInput input = (ProcessInput) codec.decode(
                        new EncodedPayload(deadline.inputType(), deadline.inputPayload()));
                CommandContext context = CommandContext.root(
                        deadline.deadlineId() + "#" + deadline.generation(), null);
                runtime.handle(instance.get().ref(), input, context);
                deadlines.markFired(deadlineId, leaseToken, clock.instant());
                return null;
            });
            return true;
        } catch (ProcessSuspendedException suspended) {
            // The instance was suspended between claim and fire: release for after resume.
            deadlines.scheduleRetry(deadlineId, leaseToken,
                    clock.instant().plus(leaseDuration), "instance suspended", clock.instant());
            return false;
        } catch (RuntimeException failure) {
            onFailure(deadline, leaseToken, failure);
            return false;
        }
    }

    private void onFailure(DeadlineRow deadline, String leaseToken, RuntimeException failure) {
        String error = describe(failure);
        if (deadline.attempts() >= retryPolicy.maxAttempts()) {
            unitOfWork.execute(() -> {
                int dead = deadlines.markDead(deadline.deadlineId(), leaseToken, error, clock.instant());
                if (dead == 1) {
                    ProcessInstanceRow row = instances.find(deadline.instanceId()).orElse(null);
                    if (row != null && row.lifecycle().isActive()) {
                        instances.suspend(
                                deadline.instanceId(), row.lifecycle(),
                                "deadline " + deadline.deadlineId() + " exhausted retries", "DEADLINE",
                                deadline.deadlineId(), clock.instant());
                    }
                }
                return null;
            });
        } else {
            Instant nextAttempt = clock.instant().plus(retryPolicy.backoff(deadline.attempts()));
            deadlines.scheduleRetry(deadline.deadlineId(), leaseToken, nextAttempt, error, clock.instant());
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getClass().getName() + ": " + failure.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
