package com.aipersimmon.ddd.processmanager.engine.runtime;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Captured;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.PublishIntegrationEvent;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Persists the durable outcome of one process advance: the appended transition-log row, the staged
 * command/event effects, and the deadline changes. Extracted from {@link DefaultProcessRuntime} —
 * this is the "write side" of a decision, cohesive around the transition/effect/deadline stores,
 * and distinct from the runtime's orchestration. Called under the instance row lock, so the
 * per-effect ordering base is stable across concurrent advances of the same instance.
 *
 * <p>It also enforces the one durable invariant that spans a decision and its timers: an ended
 * instance has no live deadline. Staged effects still deliver after a terminal decision (the final
 * event of a flow is exactly such an effect) — only the timers are cancelled.
 */
final class ProcessOutcomeWriter {

  private final ProcessTransitionStore transitions;
  private final ProcessEffectStore effects;
  private final ProcessDeadlineStore deadlines;
  private final ProcessPayloadSerdes serdes;
  private final StoreAndForwardTracer storeTracer;
  private final Supplier<String> idGenerator;

  ProcessOutcomeWriter(
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessPayloadSerdes serdes,
      StoreAndForwardTracer storeTracer,
      Supplier<String> idGenerator) {
    this.transitions = transitions;
    this.effects = effects;
    this.deadlines = deadlines;
    this.serdes = serdes;
    this.storeTracer = storeTracer;
    this.idGenerator = idGenerator;
  }

  void appendTransition(
      ProcessRef ref,
      String transitionId,
      CommandContext cause,
      ProcessInput input,
      Optional<ProcessLifecycle> fromLifecycle,
      Optional<ProcessStep> fromStep,
      ProcessDecision<Object> decision,
      String kind,
      Instant now) {
    EncodedPayload encodedInput = serdes.encodePayload(input);
    transitions.append(
        new ProcessTransitionInsert(
            cause.tenantId().value(),
            transitionId,
            ref.instanceId(),
            cause.messageId(),
            encodedInput.type().logicalType(),
            encodedInput.type().version(),
            encodedInput.data(),
            fromLifecycle,
            decision.lifecycle(),
            fromStep,
            decision.step(),
            decision.decisionCode(),
            kind,
            cause.correlationId()),
        now);
  }

  void stageEffects(
      ProcessRef ref,
      String transitionId,
      ProcessDecision<Object> decision,
      CommandContext cause,
      Instant now) {
    boolean terminal = decision.lifecycle().isTerminal();
    if (terminal) {
      rejectDeadlineOnTerminal(ref, decision);
    }
    // One monotonic base per transition; the per-instance ordering key is seqBase + index. This
    // runs under the instance row lock, so the base is stable across concurrent advances of the
    // instance.
    long seqBase = effects.nextSeq(ref.instanceId());
    int index = 0;
    for (ProcessEffect effect : decision.effects()) {
      switch (effect) {
        case DispatchCommand dispatch ->
            stageMessageEffect(
                ref,
                transitionId,
                index,
                seqBase + index,
                dispatch,
                serdes.encodePayload(dispatch.command()),
                cause,
                now);
        case PublishIntegrationEvent publish ->
            stageMessageEffect(
                ref,
                transitionId,
                index,
                seqBase + index,
                publish,
                serdes.encodePayload(publish.event()),
                cause,
                now);
        case ScheduleDeadline schedule -> scheduleDeadline(ref, schedule, cause, now);
        case CancelDeadline cancel -> deadlines.cancelCurrent(ref.instanceId(), cancel.name(), now);
      }
      index++;
    }
    if (terminal) {
      // The instance has ended, so nothing it is still waiting for can ever advance it: cancel
      // every live timer in this same advance transaction. Left behind, they would be permanent
      // garbage — the claim query only offers deadlines of active instances, so no worker would
      // ever pick them up, while a due PENDING row keeps the backlog SLI (and with it the health
      // indicator) DEGRADED forever with a monotonically growing age. The whole-instance
      // max-lifetime backstop armed at start is the one every ended instance would otherwise leave.
      deadlines.cancelLive(ref.instanceId(), now);
    }
  }

  /**
   * A terminal decision must not schedule a deadline: the claim query only offers deadlines of
   * active instances, so such a timer could never fire, and the cancellation below would settle it
   * immediately anyway. Rather than silently discard what the definition asked for, say so — this
   * is a definition bug, and one that would otherwise look like a timeout that simply never
   * arrived.
   */
  private static void rejectDeadlineOnTerminal(ProcessRef ref, ProcessDecision<Object> decision) {
    for (ProcessEffect effect : decision.effects()) {
      if (effect instanceof ScheduleDeadline schedule) {
        throw new IllegalStateException(
            "decision for instance "
                + ref.instanceId().value()
                + " ends the process as "
                + decision.lifecycle()
                + " yet schedules deadline '"
                + schedule.name().value()
                + "'; a deadline on an ended instance can never fire, so schedule it in a"
                + " non-terminal decision or drop it");
      }
    }
  }

  void scheduleDeadline(
      ProcessRef ref, ScheduleDeadline schedule, CommandContext cause, Instant now) {
    long generation = deadlines.nextGeneration(ref.instanceId(), schedule.name());
    EncodedPayload input = serdes.encodePayload(schedule.input());
    // Persist the scheduling cause's correlation/causation so the timer fires under the same
    // causal chain as the flow that armed it, rather than starting a fresh correlation.
    Captured captured = storeTracer.captureCurrent();
    deadlines.schedule(
        new ProcessDeadlineInsert(
            cause.tenantId().value(),
            idGenerator.get(),
            ref.instanceId(),
            schedule.name(),
            generation,
            schedule.dueAt(),
            input.type().logicalType(),
            input.type().version(),
            input.data(),
            cause.correlationId(),
            cause.messageId(),
            captured.traceparent(),
            captured.traceState()),
        now);
  }

  private void stageMessageEffect(
      ProcessRef ref,
      String transitionId,
      int index,
      long seq,
      ProcessEffect effect,
      EncodedPayload payload,
      CommandContext cause,
      Instant now) {
    String effectId = transitionId + "#" + index;
    // Capture the advance's trace context so the relay can link effect.dispatch back to it.
    Captured captured = storeTracer.captureCurrent();
    effects.insert(
        new ProcessEffectInsert(
            cause.tenantId().value(),
            effectId,
            ref.instanceId(),
            transitionId,
            index,
            seq,
            effect.kind(),
            payload.type().logicalType(),
            payload.type().version(),
            payload.data(),
            effectId,
            cause.correlationId(),
            cause.messageId(),
            captured.traceparent(),
            captured.traceState()),
        now);
  }
}
