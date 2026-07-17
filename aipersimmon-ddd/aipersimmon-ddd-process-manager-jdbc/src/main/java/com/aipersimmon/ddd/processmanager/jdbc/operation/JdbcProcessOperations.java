package com.aipersimmon.ddd.processmanager.jdbc.operation;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException;
import com.aipersimmon.ddd.processmanager.exception.ProcessSuspendedException;
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.ClaimedEffect;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore.ParkedInput;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Operator recovery independent of the business runtime (design-00004 §4.10). Every
 * action leaves an audited operator transition; none edits state or step arbitrarily.
 *
 * <p>{@link #redriveEffect} returns a DEAD effect to PENDING (reusing its id) and, once
 * the instance has no other DEAD effect, resumes it to its recorded resume lifecycle and
 * replays the inputs parked while it was suspended, in arrival order.
 * {@link #cancelProcess} terminates the coordinator and cancels its not-yet-dispatched
 * effects and deadlines — it does not send compensation; business cancellation stays a
 * process input decided by the definition.
 */
public final class JdbcProcessOperations {

    private final JdbcProcessInstanceStore instances;
    private final JdbcProcessTransitionStore transitions;
    private final JdbcProcessEffectStore effects;
    private final JdbcProcessDeadlineStore deadlines;
    private final ProcessRuntime runtime;
    private final ProcessPayloadCodecRegistry payloadCodecs;
    private final JdbcProcessUnitOfWork unitOfWork;
    private final Clock clock;
    private final Supplier<String> idGenerator;

    public JdbcProcessOperations(
            JdbcProcessInstanceStore instances,
            JdbcProcessTransitionStore transitions,
            JdbcProcessEffectStore effects,
            JdbcProcessDeadlineStore deadlines,
            ProcessRuntime runtime,
            ProcessPayloadCodecRegistry payloadCodecs,
            JdbcProcessUnitOfWork unitOfWork,
            Clock clock,
            Supplier<String> idGenerator) {
        this.instances = instances;
        this.transitions = transitions;
        this.effects = effects;
        this.deadlines = deadlines;
        this.runtime = runtime;
        this.payloadCodecs = payloadCodecs;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    /** Redrive one DEAD effect; resumes and replays parked inputs if it was the last one. */
    public void redriveEffect(String effectId, String operator, String reason) {
        ProcessRef resumed = unitOfWork.execute(() -> {
            ClaimedEffect effect = effects.load(effectId)
                    .orElseThrow(() -> new IllegalArgumentException("no effect " + effectId));
            if (effects.redrive(effectId, clock.instant()) == 0) {
                throw new IllegalStateException("effect " + effectId + " is not DEAD");
            }
            ProcessInstanceRow row = instances.find(effect.instanceId())
                    .orElseThrow(() -> new IllegalStateException("effect without instance"));
            transitions.appendOperator(
                    idGenerator.get(), effect.instanceId(), row.lifecycle(), row.lifecycle(),
                    row.step(), row.step(), "OPERATOR_REDRIVE_EFFECT", operator, reason, clock.instant());

            if (canResume(row.lifecycle(), effect.instanceId())) {
                ProcessLifecycle resume = row.resumeLifecycle().orElse(ProcessLifecycle.RUNNING);
                instances.resume(effect.instanceId(), resume, clock.instant());
                return row.ref();
            }
            return null;
        });
        if (resumed != null) {
            replayParkedInputs(resumed);
        }
    }

    /** Redrive one DEAD deadline; resumes and replays parked inputs if no dead work remains. */
    public void redriveDeadline(String deadlineId, long generation, String operator, String reason) {
        ProcessRef resumed = unitOfWork.execute(() -> {
            JdbcProcessDeadlineStore.DeadlineRow deadline = deadlines.load(deadlineId)
                    .orElseThrow(() -> new IllegalArgumentException("no deadline " + deadlineId));
            if (deadline.generation() != generation) {
                throw new IllegalStateException("deadline " + deadlineId + " is at generation "
                        + deadline.generation() + ", not the expected " + generation);
            }
            if (deadlines.redrive(deadlineId, clock.instant()) == 0) {
                throw new IllegalStateException("deadline " + deadlineId + " is not DEAD");
            }
            ProcessInstanceRow row = instances.find(deadline.instanceId())
                    .orElseThrow(() -> new IllegalStateException("deadline without instance"));
            transitions.appendOperator(
                    idGenerator.get(), deadline.instanceId(), row.lifecycle(), row.lifecycle(),
                    row.step(), row.step(), "OPERATOR_REDRIVE_DEADLINE", operator, reason, clock.instant());

            if (canResume(row.lifecycle(), deadline.instanceId())) {
                ProcessLifecycle resume = row.resumeLifecycle().orElse(ProcessLifecycle.RUNNING);
                instances.resume(deadline.instanceId(), resume, clock.instant());
                return row.ref();
            }
            return null;
        });
        if (resumed != null) {
            replayParkedInputs(resumed);
        }
    }

    /** A suspended instance may resume once no DEAD effect or deadline remains to hold it back. */
    private boolean canResume(ProcessLifecycle lifecycle, com.aipersimmon.ddd.processmanager.model.ProcessInstanceId instanceId) {
        return lifecycle == ProcessLifecycle.SUSPENDED
                && effects.countDead(instanceId) == 0
                && deadlines.countDead(instanceId) == 0;
    }

    private void replayParkedInputs(ProcessRef ref) {
        for (ParkedInput parked : transitions.findParkedInputs(ref.instanceId())) {
            String replayId = "parked:" + parked.inputMessageId();
            if (transitions.findTransitionIdByInput(ref.instanceId(), replayId).isPresent()) {
                continue; // already replayed (idempotent)
            }
            ProcessPayloadCodec<?> codec = payloadCodecs.forType(parked.inputType());
            ProcessInput input = (ProcessInput) codec.decode(
                    new EncodedPayload(parked.inputType(), parked.inputPayload()));
            CommandContext context = new CommandContext(replayId, replayId, parked.inputMessageId(), null);
            try {
                runtime.handle(ref, input, context);
            } catch (ProcessSuspendedException reSuspended) {
                break; // a replayed input re-suspended the instance; stop and await the next redrive
            }
        }
    }

    /** Cancel the coordinator: terminate it and cancel pending effects/deadlines. No compensation. */
    public void cancelProcess(ProcessRef ref, long expectedRevision, String operator, String reason) {
        unitOfWork.execute(() -> {
            ProcessInstanceRow row = instances.findForUpdate(ref.instanceId())
                    .orElseThrow(() -> new ProcessNotFoundException(ref));
            if (row.revision().value() != expectedRevision) {
                throw new StaleProcessRevisionException(
                        ref, new ProcessRevision(expectedRevision), row.revision());
            }
            if (row.lifecycle().isTerminal()) {
                return null; // already ended: idempotent no-op
            }
            ProcessRevision next = row.revision().next();
            ProcessInstanceRow cancelled = new ProcessInstanceRow(
                    ref, row.definitionVersion(), row.stateSchemaVersion(),
                    ProcessLifecycle.CANCELLED, row.step(), Optional.of(new ProcessOutcome("PROCESS_CANCELLED")),
                    next, row.statePayloadType(), row.statePayload(), Optional.empty(), Optional.empty());
            instances.updateSnapshot(cancelled, row.revision(), clock.instant());
            effects.cancelPending(ref.instanceId(), clock.instant());
            deadlines.cancelPending(ref.instanceId(), clock.instant());
            transitions.appendOperator(
                    idGenerator.get(), ref.instanceId(), row.lifecycle(), ProcessLifecycle.CANCELLED,
                    row.step(), row.step(), "OPERATOR_CANCEL", operator, reason, clock.instant());
            return null;
        });
    }
}
