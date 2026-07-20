package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
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
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
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
 * once attempts are exhausted, moves it to {@code DEAD} and suspends the instance.
 * A crash before the delivered mark leaves the row {@code IN_FLIGHT}
 * with an expiring lease, so it is re-claimed and re-delivered under the same id.
 *
 * <p>Before external dispatch it re-checks the owning instance's lifecycle: if an operator
 * cancel landed while the effect was in flight, the instance is now {@code CANCELLED}, so the
 * dispatch is skipped and the effect is fenced to a terminal {@code CANCELLED} state under its
 * lease — no charge/reserve command escapes. This makes operator cancel honour "no new external
 * side effect after cancel returns", not merely "cancel the not-yet-claimed effects".
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
    private final StoreAndForwardTracer storeTracer;

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
                clock, workerId, batchSize, leaseDuration, leaseTokens, ProcessObserver.NOOP,
                NoOpStoreAndForwardTracer.INSTANCE);
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
        this(jdbc, dialect, effects, instances, payloadCodecs, dispatchers, unitOfWork, retryPolicy,
                clock, workerId, batchSize, leaseDuration, leaseTokens, observer,
                NoOpStoreAndForwardTracer.INSTANCE);
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
            ProcessObserver observer,
            StoreAndForwardTracer storeTracer) {
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
        this.storeTracer = storeTracer;
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
        // Cancellation fence: if an operator cancel landed while this effect was in flight, its instance
        // is now terminally CANCELLED. Skip the external dispatch and retire the effect under our lease,
        // so no side effect escapes after cancel returned.
        Optional<ProcessInstanceRow> owner = instances.find(effect.instanceId());
        if (owner.isPresent() && owner.get().lifecycle() == ProcessLifecycle.CANCELLED) {
            effects.markCancelled(effectId, leaseToken, clock.instant());
            return false;
        }
        long dispatchStart = System.nanoTime();
        try {
            ProcessPayloadCodec<?> codec = payloadCodecs.forType(effect.payloadType());
            Object payload = codec.decode(new EncodedPayload(effect.payloadType(), effect.payload()));
            // Restore the advance's trace context so this dispatch (and any command/event it emits,
            // whose producer instrumentation reads the current context) links back to the span that
            // staged the effect, rather than starting an unrelated trace on the relay thread.
            try (StoreAndForwardTracer.Scope span = storeTracer.restore(
                    effect.traceparent(), effect.traceState(), "effect.dispatch " + effectId)) {
                try {
                    dispatchers.dispatch(
                            new DecodedProcessEffect(effect.effectId(), effect.instanceId(), effect.kind(), payload),
                            effect.context());
                } catch (RuntimeException e) {
                    span.recordFailure(e);
                    throw e;
                }
            }
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
        // attempts is bumped by scheduleRetry/markDead (a failed attempt), not by claiming, so a slow
        // worker's lease-expiry reclaim never consumes the retry budget. This failure is attempt N+1.
        int attempt = effect.attempts() + 1;
        if (attempt >= retryPolicy.maxAttempts()) {
            unitOfWork.execute(() -> {
                int dead = effects.markDead(effect.effectId(), leaseToken, error, clock.instant());
                if (dead == 1) {
                    ProcessInstanceRow row = instances.findForUpdate(effect.instanceId()).orElse(null);
                    if (row != null && row.lifecycle().canSuspend()) {
                        instances.suspend(
                                effect.instanceId(), row.lifecycle(),
                                "effect " + effect.effectId() + " exhausted retries", "EFFECT",
                                effect.effectId(), clock.instant());
                    }
                }
                return null;
            });
        } else {
            Instant nextAttempt = clock.instant().plus(retryPolicy.backoff(attempt));
            effects.scheduleRetry(effect.effectId(), leaseToken, nextAttempt, error, clock.instant());
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getClass().getName() + ": " + failure.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
