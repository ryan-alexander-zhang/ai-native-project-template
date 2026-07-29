package com.aipersimmon.ddd.processmanager.engine.operation;

import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.store.ClaimedEffect;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException;
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Operator recovery independent of the business runtime. Every action leaves an audited operator
 * transition; none edits state or step arbitrarily.
 *
 * <p>{@link #redriveEffect} returns a DEAD effect to PENDING (reusing its id) and, once the
 * instance has no other DEAD effect, resumes it to its recorded resume lifecycle — in one
 * transaction, which is all an operator action needs to be. Inputs parked during the suspension are
 * then replayed by the parked-input worker off the durable queue, not inside this call: an
 * operator's request must not be the only place a replay debt exists, or a crash right after the
 * resume commits would lose it.
 *
 * <p>{@link #cancelProcess} terminates the coordinator and cancels its not-yet-dispatched effects
 * and its live deadlines. It also fences effects already claimed IN_FLIGHT: the relay re-checks the
 * owning instance before external dispatch and skips any whose instance is cancelled, so after
 * cancel returns no new external side effect is emitted. It does not send compensation; business
 * cancellation stays a process input decided by the definition.
 */
public final class ProcessOperations {

  private final ProcessInstanceStore instances;
  private final ProcessTransitionStore transitions;
  private final ProcessEffectStore effects;
  private final ProcessDeadlineStore deadlines;
  private final ProcessUnitOfWork unitOfWork;
  private final Clock clock;
  private final Supplier<String> idGenerator;

  public ProcessOperations(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      Supplier<String> idGenerator) {
    this.instances = instances;
    this.transitions = transitions;
    this.effects = effects;
    this.deadlines = deadlines;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.idGenerator = idGenerator;
  }

  /** Redrive one DEAD effect; resumes the instance if it was the last thing holding it back. */
  public void redriveEffect(String effectId, String operator, String reason) {
    unitOfWork.execute(
        () -> {
          ClaimedEffect effect =
              effects
                  .load(effectId)
                  .orElseThrow(() -> new IllegalArgumentException("no effect " + effectId));
          if (effects.redrive(effectId, clock.instant()) == 0) {
            throw new IllegalStateException("effect " + effectId + " is not DEAD");
          }
          // Lock the instance so a concurrent redrive of another DEAD item on the same instance
          // can't race the dead-count check and leave the instance SUSPENDED with no DEAD work
          // remaining.
          ProcessInstanceRow row =
              instances
                  .findForUpdate(effect.instanceId())
                  .orElseThrow(() -> new IllegalStateException("effect without instance"));
          transitions.appendOperator(
              row.tenantId(),
              idGenerator.get(),
              effect.instanceId(),
              row.lifecycle(),
              row.lifecycle(),
              row.step(),
              row.step(),
              "OPERATOR_REDRIVE_EFFECT",
              operator,
              reason,
              clock.instant());

          if (canResume(row.lifecycle(), effect.instanceId())) {
            ProcessLifecycle resume = row.resumeLifecycle().orElse(ProcessLifecycle.RUNNING);
            instances.resume(effect.instanceId(), resume, clock.instant());
          }
          return null;
        });
  }

  /** Redrive one DEAD deadline; resumes the instance if no dead work remains to hold it back. */
  public void redriveDeadline(String deadlineId, long generation, String operator, String reason) {
    unitOfWork.execute(
        () -> {
          DeadlineRow deadline =
              deadlines
                  .load(deadlineId)
                  .orElseThrow(() -> new IllegalArgumentException("no deadline " + deadlineId));
          if (deadline.generation() != generation) {
            throw new IllegalStateException(
                "deadline "
                    + deadlineId
                    + " is at generation "
                    + deadline.generation()
                    + ", not the expected "
                    + generation);
          }
          if (deadlines.redrive(deadlineId, clock.instant()) == 0) {
            throw new IllegalStateException("deadline " + deadlineId + " is not DEAD");
          }
          // Lock the instance so a concurrent redrive of another DEAD item can't race the
          // dead-count check.
          ProcessInstanceRow row =
              instances
                  .findForUpdate(deadline.instanceId())
                  .orElseThrow(() -> new IllegalStateException("deadline without instance"));
          transitions.appendOperator(
              row.tenantId(),
              idGenerator.get(),
              deadline.instanceId(),
              row.lifecycle(),
              row.lifecycle(),
              row.step(),
              row.step(),
              "OPERATOR_REDRIVE_DEADLINE",
              operator,
              reason,
              clock.instant());

          if (canResume(row.lifecycle(), deadline.instanceId())) {
            ProcessLifecycle resume = row.resumeLifecycle().orElse(ProcessLifecycle.RUNNING);
            instances.resume(deadline.instanceId(), resume, clock.instant());
          }
          return null;
        });
  }

  /** A suspended instance may resume once no DEAD effect or deadline remains to hold it back. */
  private boolean canResume(
      ProcessLifecycle lifecycle,
      com.aipersimmon.ddd.processmanager.model.ProcessInstanceId instanceId) {
    return lifecycle == ProcessLifecycle.SUSPENDED
        && effects.countDead(instanceId) == 0
        && deadlines.countDead(instanceId) == 0;
  }

  /**
   * Cancel the coordinator: terminate it, cancel its not-yet-dispatched effects and every live
   * deadline. Effects already claimed IN_FLIGHT are fenced by the relay's pre-dispatch lifecycle
   * re-check, so no new external side effect is emitted after this returns. No compensation.
   */
  public void cancelProcess(ProcessRef ref, long expectedRevision, String operator, String reason) {
    unitOfWork.execute(
        () -> {
          ProcessInstanceRow row =
              instances
                  .findForUpdate(ref.instanceId())
                  .orElseThrow(() -> new ProcessNotFoundException(ref));
          row.requireRefMatches(ref);
          if (row.revision().value() != expectedRevision) {
            throw new StaleProcessRevisionException(
                ref, new ProcessRevision(expectedRevision), row.revision());
          }
          if (row.lifecycle().isTerminal()) {
            return null; // already ended: idempotent no-op
          }
          ProcessRevision next = row.revision().next();
          ProcessInstanceRow cancelled =
              new ProcessInstanceRow(
                  row.tenantId(),
                  ref,
                  row.definitionVersion(),
                  row.stateSchemaVersion(),
                  ProcessLifecycle.CANCELLED,
                  row.step(),
                  Optional.of(new ProcessOutcome("PROCESS_CANCELLED")),
                  next,
                  row.statePayloadType(),
                  row.statePayload(),
                  Optional.empty(),
                  Optional.empty());
          instances.updateSnapshot(cancelled, row.revision(), clock.instant());
          effects.cancelPending(ref.instanceId(), clock.instant());
          deadlines.cancelLive(ref.instanceId(), clock.instant());
          transitions.appendOperator(
              row.tenantId(),
              idGenerator.get(),
              ref.instanceId(),
              row.lifecycle(),
              ProcessLifecycle.CANCELLED,
              row.step(),
              row.step(),
              "OPERATOR_CANCEL",
              operator,
              reason,
              clock.instant());
          return null;
        });
  }
}
