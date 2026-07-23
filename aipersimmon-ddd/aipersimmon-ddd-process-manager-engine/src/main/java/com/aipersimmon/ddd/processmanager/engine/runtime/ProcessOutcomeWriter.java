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
