package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.jdbc.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.ClaimedEffect;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Delivers staged effects at-least-once, out of the advance transaction. One
 * {@link #pollOnce()} claims a batch of due, per-instance-ordered effects (via the
 * {@link JdbcProcessDialect}, marking them {@code IN_FLIGHT} with a fresh lease), then
 * for each: decodes the payload, dispatches under the reconstructed context, and — fenced
 * by that lease token — marks it {@code DELIVERED}, schedules a bounded backoff retry, or,
 * once attempts are exhausted, moves it to {@code DEAD} and suspends the instance
 * (design-00004 §4.6). A crash before the delivered mark leaves the row {@code IN_FLIGHT}
 * with an expiring lease, so it is re-claimed and re-delivered under the same id.
 *
 * <p>The scheduling loop that calls {@code pollOnce} on an interval is the starter's
 * concern; this class is the directly testable unit of work.
 */
public final class JdbcProcessEffectRelay {

    private final JdbcTemplate jdbc;
    private final JdbcProcessDialect dialect;
    private final JdbcProcessEffectStore effects;
    private final JdbcProcessInstanceStore instances;
    private final ProcessPayloadCodecRegistry payloadCodecs;
    private final EffectDispatcherRegistry dispatchers;
    private final JdbcProcessUnitOfWork unitOfWork;
    private final ProcessRetryPolicy retryPolicy;
    private final java.time.Clock clock;
    private final WorkerId workerId;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Supplier<String> leaseTokens;
    private final ProcessObserver observer;

    public JdbcProcessEffectRelay(
            JdbcTemplate jdbc,
            JdbcProcessDialect dialect,
            JdbcProcessEffectStore effects,
            JdbcProcessInstanceStore instances,
            ProcessPayloadCodecRegistry payloadCodecs,
            EffectDispatcherRegistry dispatchers,
            JdbcProcessUnitOfWork unitOfWork,
            ProcessRetryPolicy retryPolicy,
            java.time.Clock clock,
            WorkerId workerId,
            int batchSize,
            Duration leaseDuration,
            Supplier<String> leaseTokens) {
        this(jdbc, dialect, effects, instances, payloadCodecs, dispatchers, unitOfWork, retryPolicy,
                clock, workerId, batchSize, leaseDuration, leaseTokens, ProcessObserver.NOOP);
    }

    public JdbcProcessEffectRelay(
            JdbcTemplate jdbc,
            JdbcProcessDialect dialect,
            JdbcProcessEffectStore effects,
            JdbcProcessInstanceStore instances,
            ProcessPayloadCodecRegistry payloadCodecs,
            EffectDispatcherRegistry dispatchers,
            JdbcProcessUnitOfWork unitOfWork,
            ProcessRetryPolicy retryPolicy,
            java.time.Clock clock,
            WorkerId workerId,
            int batchSize,
            Duration leaseDuration,
            Supplier<String> leaseTokens,
            ProcessObserver observer) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.effects = effects;
        this.instances = instances;
        this.payloadCodecs = payloadCodecs;
        this.dispatchers = dispatchers;
        this.unitOfWork = unitOfWork;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.workerId = workerId;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.leaseTokens = leaseTokens;
        this.observer = observer;
    }

    /** Claim and deliver one batch; returns the number delivered successfully. */
    public int pollOnce() {
        String leaseToken = leaseTokens.get();
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(leaseDuration);
        long claimStart = System.nanoTime();
        List<String> claimed = unitOfWork.execute(
                () -> dialect.claimDueEffects(jdbc, now, batchSize, workerId, leaseToken, leaseUntil));
        observer.effectClaimed(claimed.size(), Duration.ofNanos(System.nanoTime() - claimStart));

        int delivered = 0;
        for (String effectId : claimed) {
            if (deliver(effectId, leaseToken)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean deliver(String effectId, String leaseToken) {
        Optional<ClaimedEffect> loaded = effects.load(effectId);
        if (loaded.isEmpty()) {
            return false;
        }
        ClaimedEffect effect = loaded.get();
        long dispatchStart = System.nanoTime();
        try {
            ProcessPayloadCodec<?> codec = payloadCodecs.forType(effect.payloadType());
            Object payload = codec.decode(new EncodedPayload(effect.payloadType(), effect.payload()));
            dispatchers.dispatch(
                    new DecodedProcessEffect(effect.effectId(), effect.instanceId(), effect.kind(), payload),
                    effect.context());
            effects.markDelivered(effectId, leaseToken, clock.instant());
            observer.effectDispatched(true, Duration.ofNanos(System.nanoTime() - dispatchStart));
            return true;
        } catch (RuntimeException failure) {
            observer.effectDispatched(false, Duration.ofNanos(System.nanoTime() - dispatchStart));
            onFailure(effect, leaseToken, failure);
            return false;
        }
    }

    private void onFailure(ClaimedEffect effect, String leaseToken, RuntimeException failure) {
        String error = describe(failure);
        if (effect.attempts() >= retryPolicy.maxAttempts()) {
            unitOfWork.execute(() -> {
                int dead = effects.markDead(effect.effectId(), leaseToken, error, clock.instant());
                if (dead == 1) {
                    ProcessInstanceRow row = instances.find(effect.instanceId()).orElse(null);
                    if (row != null && row.lifecycle().isActive()) {
                        instances.suspend(
                                effect.instanceId(), row.lifecycle(),
                                "effect " + effect.effectId() + " exhausted retries", "EFFECT",
                                effect.effectId(), clock.instant());
                    }
                }
                return null;
            });
        } else {
            Instant nextAttempt = clock.instant().plus(retryPolicy.backoff(effect.attempts()));
            effects.scheduleRetry(effect.effectId(), leaseToken, nextAttempt, error, clock.instant());
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getClass().getName() + ": " + failure.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
