package com.aipersimmon.ddd.processmanager.engine.runtime;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.NoOpTracer;
import com.aipersimmon.ddd.observability.ObservabilityAttributes;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.MaxLifetimeExceeded;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.engine.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.engine.store.ConcurrentTransitionException;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.exception.ProcessAlreadyExistsException;
import com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException;
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The production {@link ProcessRuntime} over the four-table JDBC store. Each {@code start}/{@code
 * handle} runs the pure definition and atomically persists the new snapshot, the appended
 * transition, the staged effects, and the deadline changes in one {@code REQUIRED} transaction; a
 * relay delivers effects afterwards. Effects are staged with their durable identity — {@code
 * messageId} equal to a deterministic {@code effectId} of {@code transitionId#index} — so
 * at-least-once redelivery keeps one stable id.
 *
 * <p>Process-level idempotency comes from the {@code UNIQUE(instance_id, input_message_id)}
 * constraint (a repeated input is a duplicate no-op); optimistic concurrency comes from the
 * revision guard on the snapshot update, with a bounded retry.
 */
public final class DefaultProcessRuntime implements ProcessRuntime {

  private final ProcessInstanceStore instances;
  private final ProcessTransitionStore transitions;
  private final ProcessDefinitionRegistry definitions;
  private final ProcessPayloadSerdes serdes;
  private final ProcessOutcomeWriter outcomeWriter;
  private final ProcessUnitOfWork unitOfWork;
  private final Clock clock;
  private final Supplier<String> idGenerator;
  private final DuplicateBusinessKeyPolicy duplicatePolicy;
  private final int maxRetries;
  private final ProcessObserver observer;
  private final Optional<Duration> maxLifetime;
  private final Tracer tracer;

  public DefaultProcessRuntime(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      Supplier<String> idGenerator,
      DuplicateBusinessKeyPolicy duplicatePolicy,
      int maxRetries) {
    this(
        instances,
        transitions,
        effects,
        deadlines,
        definitions,
        payloadCodecs,
        stateCodecs,
        unitOfWork,
        clock,
        idGenerator,
        duplicatePolicy,
        maxRetries,
        ProcessObserver.NOOP);
  }

  public DefaultProcessRuntime(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      Supplier<String> idGenerator,
      DuplicateBusinessKeyPolicy duplicatePolicy,
      int maxRetries,
      ProcessObserver observer) {
    this(
        instances,
        transitions,
        effects,
        deadlines,
        definitions,
        payloadCodecs,
        stateCodecs,
        unitOfWork,
        clock,
        idGenerator,
        duplicatePolicy,
        maxRetries,
        observer,
        Optional.empty(),
        Long.MAX_VALUE);
  }

  public DefaultProcessRuntime(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      Supplier<String> idGenerator,
      DuplicateBusinessKeyPolicy duplicatePolicy,
      int maxRetries,
      ProcessObserver observer,
      Optional<Duration> maxLifetime,
      long maxPayloadBytes) {
    this(
        instances,
        transitions,
        effects,
        deadlines,
        definitions,
        payloadCodecs,
        stateCodecs,
        unitOfWork,
        clock,
        idGenerator,
        duplicatePolicy,
        maxRetries,
        observer,
        maxLifetime,
        maxPayloadBytes,
        NoOpTracer.INSTANCE);
  }

  public DefaultProcessRuntime(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      Supplier<String> idGenerator,
      DuplicateBusinessKeyPolicy duplicatePolicy,
      int maxRetries,
      ProcessObserver observer,
      Optional<Duration> maxLifetime,
      long maxPayloadBytes,
      Tracer tracer) {
    this(
        instances,
        transitions,
        effects,
        deadlines,
        definitions,
        payloadCodecs,
        stateCodecs,
        unitOfWork,
        clock,
        idGenerator,
        duplicatePolicy,
        maxRetries,
        observer,
        maxLifetime,
        maxPayloadBytes,
        tracer,
        NoOpStoreAndForwardTracer.INSTANCE);
  }

  public DefaultProcessRuntime(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessEffectStore effects,
      ProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      Supplier<String> idGenerator,
      DuplicateBusinessKeyPolicy duplicatePolicy,
      int maxRetries,
      ProcessObserver observer,
      Optional<Duration> maxLifetime,
      long maxPayloadBytes,
      Tracer tracer,
      StoreAndForwardTracer storeTracer) {
    this.instances = instances;
    this.transitions = transitions;
    this.definitions = definitions;
    this.serdes = new ProcessPayloadSerdes(payloadCodecs, stateCodecs, maxPayloadBytes);
    this.outcomeWriter =
        new ProcessOutcomeWriter(
            transitions, effects, deadlines, this.serdes, storeTracer, idGenerator);
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.idGenerator = idGenerator;
    this.duplicatePolicy = duplicatePolicy;
    this.maxRetries = maxRetries;
    this.observer = observer;
    this.maxLifetime = maxLifetime;
    this.tracer = tracer;
  }

  @Override
  public ProcessAdvanceResult start(
      ProcessType processType,
      ProcessBusinessKey businessKey,
      ProcessInput input,
      CommandContext cause) {
    return traced(
        processType.value(),
        businessKey.value(),
        () ->
            withRetry(
                () -> unitOfWork.execute(() -> doStart(processType, businessKey, input, cause))));
  }

  @Override
  public ProcessAdvanceResult handle(
      ProcessRef processRef, ProcessInput input, CommandContext cause) {
    return traced(
        processRef.processType().value(),
        processRef.businessKey().value(),
        () -> withRetry(() -> unitOfWork.execute(() -> doHandle(processRef, input, cause))));
  }

  /**
   * Opens a {@code process.advance} span around one advance so the decision is visible in traces —
   * both under a command and, once a relay or deadline worker drives it, under the restored
   * (linked) context where nothing else would name it. The span wraps retries and the transaction;
   * result lifecycle/step are stamped on success, the exception on failure.
   */
  private ProcessAdvanceResult traced(
      String processType, String businessKey, Supplier<ProcessAdvanceResult> advance) {
    try (Tracer.SpanScope span = tracer.startSpan("process.advance " + processType)) {
      span.attribute(ObservabilityAttributes.PROCESS_TYPE, processType)
          .attribute(ObservabilityAttributes.PROCESS_BUSINESS_KEY, businessKey);
      try {
        ProcessAdvanceResult result = advance.get();
        span.attribute(
                ObservabilityAttributes.PROCESS_INSTANCE_ID,
                result.processRef().instanceId().value())
            .attribute(ObservabilityAttributes.LIFECYCLE, result.lifecycle().name());
        if (result.step() != null) {
          span.attribute(ObservabilityAttributes.STEP, result.step().value());
        }
        return result;
      } catch (RuntimeException e) {
        span.error(e);
        throw e;
      }
    }
  }

  private ProcessAdvanceResult doStart(
      ProcessType processType,
      ProcessBusinessKey businessKey,
      ProcessInput input,
      CommandContext cause) {
    ProcessDefinition<?> definition = definitions.resolveActive(processType);

    Optional<ProcessInstanceRow> existing = instances.findByBusinessKey(processType, businessKey);
    if (existing.isPresent()) {
      return resolveExistingStart(existing.get(), processType, businessKey, cause);
    }

    ProcessInstanceId instanceId = new ProcessInstanceId(idGenerator.get());
    ProcessRef ref = new ProcessRef(instanceId, processType, businessKey);
    Instant now = clock.instant();
    ProcessContext context =
        new ProcessContext(
            ref,
            ProcessRevision.initial(),
            definition.definitionVersion(),
            Optional.empty(),
            Optional.empty(),
            now,
            cause);

    ProcessDecision<Object> decision = callStart(definition, input, context);
    ProcessRevision revision = ProcessRevision.initial().next();
    String transitionId = idGenerator.get();

    EncodedPayload state =
        serdes.encodeState(processType, definition.stateSchemaVersion(), decision.state());
    instances.insert(
        new ProcessInstanceRow(
            ref,
            definition.definitionVersion(),
            definition.stateSchemaVersion(),
            decision.lifecycle(),
            decision.step(),
            decision.outcome(),
            revision,
            state.type().logicalType(),
            state.data(),
            Optional.empty(),
            Optional.empty()),
        now);

    outcomeWriter.appendTransition(
        ref,
        transitionId,
        cause,
        input,
        Optional.empty(),
        Optional.empty(),
        decision,
        "START",
        now);
    outcomeWriter.stageEffects(ref, transitionId, decision, cause, now);
    armMaxLifetimeBackstop(ref, decision, cause, now);

    return new ProcessAdvanceResult(
        ref, revision, decision.lifecycle(), decision.step(), false, transitionId);
  }

  /**
   * Arm the whole-instance max-lifetime backstop when configured and the instance is still active
   * after start. It is an ordinary deadline the definition can later reschedule to extend, or that
   * fires {@link MaxLifetimeExceeded} into {@code handle} for the definition to decide. A
   * definition that schedules or cancels the reserved name in its own start decision owns that
   * timer: the default backstop steps aside rather than clobbering it with a higher generation, so
   * the definition's decision (a custom due time, or an outright cancellation) is what stands.
   */
  private void armMaxLifetimeBackstop(
      ProcessRef ref, ProcessDecision<Object> decision, CommandContext cause, Instant now) {
    if (maxLifetime.isEmpty() || !decision.lifecycle().isActive()) {
      return;
    }
    if (decisionTouchesReservedDeadline(decision)) {
      return;
    }
    outcomeWriter.scheduleDeadline(
        ref,
        new ScheduleDeadline(
            MaxLifetimeExceeded.DEADLINE_NAME,
            now.plus(maxLifetime.get()),
            new MaxLifetimeExceeded()),
        cause,
        now);
  }

  /** Whether the decision already schedules or cancels the reserved max-lifetime deadline name. */
  private static boolean decisionTouchesReservedDeadline(ProcessDecision<Object> decision) {
    for (ProcessEffect effect : decision.effects()) {
      boolean touches =
          switch (effect) {
            case ScheduleDeadline schedule ->
                schedule.name().equals(MaxLifetimeExceeded.DEADLINE_NAME);
            case CancelDeadline cancel -> cancel.name().equals(MaxLifetimeExceeded.DEADLINE_NAME);
            default -> false;
          };
      if (touches) {
        return true;
      }
    }
    return false;
  }

  private ProcessAdvanceResult resolveExistingStart(
      ProcessInstanceRow row,
      ProcessType processType,
      ProcessBusinessKey businessKey,
      CommandContext cause) {
    Optional<String> duplicate =
        transitions.findTransitionIdByInput(row.ref().instanceId(), cause.messageId());
    if (duplicate.isPresent()) {
      return duplicateResult(row, duplicate.get());
    }
    if (duplicatePolicy == DuplicateBusinessKeyPolicy.REJECT) {
      throw new ProcessAlreadyExistsException(processType, businessKey);
    }
    String latest =
        transitions
            .findLatestTransitionId(row.ref().instanceId())
            .orElseThrow(() -> new IllegalStateException("instance without any transition"));
    return duplicateResult(row, latest);
  }

  private ProcessAdvanceResult doHandle(ProcessRef ref, ProcessInput input, CommandContext cause) {
    ProcessInstanceRow row =
        instances
            .findForUpdate(ref.instanceId())
            .orElseThrow(() -> new ProcessNotFoundException(ref));
    row.requireRefMatches(ref);

    Optional<String> duplicate =
        transitions.findTransitionIdByInput(ref.instanceId(), cause.messageId());
    if (duplicate.isPresent()) {
      return duplicateResult(row, duplicate.get());
    }
    if (row.lifecycle() == ProcessLifecycle.SUSPENDED) {
      // Do not rebound to the message layer: park the input as an audit transition (deduped by
      // the UNIQUE input message id) and return, so the transport can ack. It is replayed in
      // arrival order when the instance resumes.
      String parkedId = idGenerator.get();
      Instant parkedAt = clock.instant();
      EncodedPayload parkedInput = serdes.encodePayload(input);
      transitions.append(
          new ProcessTransitionInsert(
              parkedId,
              ref.instanceId(),
              cause.messageId(),
              parkedInput.type().logicalType(),
              parkedInput.type().version(),
              parkedInput.data(),
              Optional.of(row.lifecycle()),
              row.lifecycle(),
              Optional.of(row.step()),
              row.step(),
              new DecisionCode("parked"),
              "PARKED",
              cause.correlationId()),
          parkedAt);
      return new ProcessAdvanceResult(
          ref, row.revision(), row.lifecycle(), row.step(), false, parkedId);
    }
    if (row.lifecycle().isTerminal()) {
      String latest =
          transitions
              .findLatestTransitionId(ref.instanceId())
              .orElseThrow(() -> new IllegalStateException("instance without any transition"));
      return duplicateResult(row, latest);
    }

    ProcessDefinition<?> definition =
        definitions.resolve(ref.processType(), row.definitionVersion());
    Object state =
        serdes.decodeState(
            ref.processType(),
            row.stateSchemaVersion(),
            row.statePayloadType(),
            row.statePayload());
    Instant now = clock.instant();
    ProcessContext context =
        new ProcessContext(
            ref,
            row.revision(),
            definition.definitionVersion(),
            Optional.of(row.lifecycle()),
            Optional.of(row.step()),
            now,
            cause);

    ProcessDecision<Object> decision = callReact(definition, state, input, context);
    if (!row.lifecycle().canTransitionTo(decision.lifecycle())) {
      throw new IllegalStateException(
          "illegal lifecycle transition "
              + row.lifecycle()
              + " -> "
              + decision.lifecycle()
              + " for instance "
              + ref.instanceId().value());
    }

    ProcessRevision revision = row.revision().next();
    String transitionId = idGenerator.get();
    EncodedPayload state2 =
        serdes.encodeState(ref.processType(), definition.stateSchemaVersion(), decision.state());
    ProcessInstanceRow updated =
        new ProcessInstanceRow(
            ref,
            definition.definitionVersion(),
            definition.stateSchemaVersion(),
            decision.lifecycle(),
            decision.step(),
            decision.outcome(),
            revision,
            state2.type().logicalType(),
            state2.data(),
            Optional.empty(),
            Optional.empty());

    int rows = instances.updateSnapshot(updated, row.revision(), now);
    if (rows == 0) {
      ProcessRevision actual =
          instances.find(ref.instanceId()).map(ProcessInstanceRow::revision).orElse(row.revision());
      throw new StaleProcessRevisionException(ref, row.revision(), actual);
    }

    outcomeWriter.appendTransition(
        ref,
        transitionId,
        cause,
        input,
        Optional.of(row.lifecycle()),
        Optional.of(row.step()),
        decision,
        "ADVANCE",
        now);
    outcomeWriter.stageEffects(ref, transitionId, decision, cause, now);

    return new ProcessAdvanceResult(
        ref, revision, decision.lifecycle(), decision.step(), false, transitionId);
  }

  private ProcessAdvanceResult duplicateResult(ProcessInstanceRow row, String transitionId) {
    return new ProcessAdvanceResult(
        row.ref(), row.revision(), row.lifecycle(), row.step(), true, transitionId);
  }

  private ProcessAdvanceResult withRetry(Supplier<ProcessAdvanceResult> attempt) {
    RuntimeException last = null;
    for (int i = 0; i <= maxRetries; i++) {
      try {
        return attempt.get();
      } catch (StaleProcessRevisionException | ConcurrentTransitionException e) {
        last = e;
        observer.advanceConflictRetry();
      }
    }
    throw last;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ProcessDecision<Object> callStart(
      ProcessDefinition<?> definition, ProcessInput input, ProcessContext ctx) {
    return ((ProcessDefinition) definition).start(input, ctx);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ProcessDecision<Object> callReact(
      ProcessDefinition<?> definition, Object state, ProcessInput input, ProcessContext ctx) {
    return ((ProcessDefinition) definition).react(state, input, ctx);
  }
}
