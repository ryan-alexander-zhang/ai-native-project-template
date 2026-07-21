package com.aipersimmon.ddd.processmanager.jdbc.runtime;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.NoOpTracer;
import com.aipersimmon.ddd.observability.ObservabilityAttributes;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer.Captured;
import com.aipersimmon.ddd.observability.Tracer;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.MaxLifetimeExceeded;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.PublishIntegrationEvent;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.exception.ProcessAlreadyExistsException;
import com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException;
import com.aipersimmon.ddd.processmanager.exception.ProcessPayloadTooLargeException;
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
import com.aipersimmon.ddd.processmanager.jdbc.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessEffectInsert;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessTransitionInsert;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;

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
public final class JdbcProcessRuntime implements ProcessRuntime {

  private final JdbcProcessInstanceStore instances;
  private final JdbcProcessTransitionStore transitions;
  private final JdbcProcessEffectStore effects;
  private final JdbcProcessDeadlineStore deadlines;
  private final ProcessDefinitionRegistry definitions;
  private final ProcessPayloadCodecRegistry payloadCodecs;
  private final ProcessStateCodecRegistry stateCodecs;
  private final JdbcProcessUnitOfWork unitOfWork;
  private final Clock clock;
  private final Supplier<String> idGenerator;
  private final DuplicateBusinessKeyPolicy duplicatePolicy;
  private final int maxRetries;
  private final ProcessObserver observer;
  private final Optional<Duration> maxLifetime;
  private final long maxPayloadBytes;
  private final Tracer tracer;
  private final StoreAndForwardTracer storeTracer;

  public JdbcProcessRuntime(
      JdbcProcessInstanceStore instances,
      JdbcProcessTransitionStore transitions,
      JdbcProcessEffectStore effects,
      JdbcProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      JdbcProcessUnitOfWork unitOfWork,
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

  public JdbcProcessRuntime(
      JdbcProcessInstanceStore instances,
      JdbcProcessTransitionStore transitions,
      JdbcProcessEffectStore effects,
      JdbcProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      JdbcProcessUnitOfWork unitOfWork,
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

  public JdbcProcessRuntime(
      JdbcProcessInstanceStore instances,
      JdbcProcessTransitionStore transitions,
      JdbcProcessEffectStore effects,
      JdbcProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      JdbcProcessUnitOfWork unitOfWork,
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

  public JdbcProcessRuntime(
      JdbcProcessInstanceStore instances,
      JdbcProcessTransitionStore transitions,
      JdbcProcessEffectStore effects,
      JdbcProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      JdbcProcessUnitOfWork unitOfWork,
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

  public JdbcProcessRuntime(
      JdbcProcessInstanceStore instances,
      JdbcProcessTransitionStore transitions,
      JdbcProcessEffectStore effects,
      JdbcProcessDeadlineStore deadlines,
      ProcessDefinitionRegistry definitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      JdbcProcessUnitOfWork unitOfWork,
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
    this.effects = effects;
    this.deadlines = deadlines;
    this.definitions = definitions;
    this.payloadCodecs = payloadCodecs;
    this.stateCodecs = stateCodecs;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.idGenerator = idGenerator;
    this.duplicatePolicy = duplicatePolicy;
    this.maxRetries = maxRetries;
    this.observer = observer;
    this.maxLifetime = maxLifetime;
    this.maxPayloadBytes = maxPayloadBytes;
    this.tracer = tracer;
    this.storeTracer = storeTracer;
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
        encodeState(processType, definition.stateSchemaVersion(), decision.state());
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

    appendTransition(
        ref,
        transitionId,
        cause,
        input,
        Optional.empty(),
        Optional.empty(),
        decision,
        "START",
        now);
    stageEffects(ref, transitionId, decision, cause, now);
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
    scheduleDeadline(
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
    requireRefMatch(ref, row);

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
      EncodedPayload parkedInput = encodePayload(input);
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
        decodeState(
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
        encodeState(ref.processType(), definition.stateSchemaVersion(), decision.state());
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

    appendTransition(
        ref,
        transitionId,
        cause,
        input,
        Optional.of(row.lifecycle()),
        Optional.of(row.step()),
        decision,
        "ADVANCE",
        now);
    stageEffects(ref, transitionId, decision, cause, now);

    return new ProcessAdvanceResult(
        ref, revision, decision.lifecycle(), decision.step(), false, transitionId);
  }

  /**
   * Fail fast when a ref carries a real instanceId but a processType/businessKey that disagrees
   * with the stored row. Identity is loaded from the row, never trusted from the caller, so a
   * mismatched ref cannot silently drive the wrong definition/codec against a real instance (the
   * same guard sits on the read query and the operator cancel).
   */
  private static void requireRefMatch(ProcessRef ref, ProcessInstanceRow row) {
    if (!row.ref().equals(ref)) {
      throw new IllegalArgumentException(
          "process ref mismatch for instance "
              + ref.instanceId().value()
              + ": supplied "
              + ref.processType().value()
              + "/"
              + ref.businessKey().value()
              + " but the stored instance is "
              + row.ref().processType().value()
              + "/"
              + row.ref().businessKey().value());
    }
  }

  private void appendTransition(
      ProcessRef ref,
      String transitionId,
      CommandContext cause,
      ProcessInput input,
      Optional<ProcessLifecycle> fromLifecycle,
      Optional<com.aipersimmon.ddd.processmanager.model.ProcessStep> fromStep,
      ProcessDecision<Object> decision,
      String kind,
      Instant now) {
    EncodedPayload encodedInput = encodePayload(input);
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

  private void stageEffects(
      ProcessRef ref,
      String transitionId,
      ProcessDecision<Object> decision,
      CommandContext cause,
      Instant now) {
    // One monotonic base per transition; the per-instance ordering key is seqBase + index. This
    // runs
    // under the instance row lock, so the base is stable across concurrent advances of the
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
                encodePayload(dispatch.command()),
                cause,
                now);
        case PublishIntegrationEvent publish ->
            stageMessageEffect(
                ref,
                transitionId,
                index,
                seqBase + index,
                publish,
                encodePayload(publish.event()),
                cause,
                now);
        case ScheduleDeadline schedule -> scheduleDeadline(ref, schedule, cause, now);
        case CancelDeadline cancel -> deadlines.cancelCurrent(ref.instanceId(), cancel.name(), now);
      }
      index++;
    }
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

  private void scheduleDeadline(
      ProcessRef ref, ScheduleDeadline schedule, CommandContext cause, Instant now) {
    long generation = deadlines.nextGeneration(ref.instanceId(), schedule.name());
    EncodedPayload input = encodePayload(schedule.input());
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

  private ProcessAdvanceResult duplicateResult(ProcessInstanceRow row, String transitionId) {
    return new ProcessAdvanceResult(
        row.ref(), row.revision(), row.lifecycle(), row.step(), true, transitionId);
  }

  private ProcessAdvanceResult withRetry(Supplier<ProcessAdvanceResult> attempt) {
    RuntimeException last = null;
    for (int i = 0; i <= maxRetries; i++) {
      try {
        return attempt.get();
      } catch (StaleProcessRevisionException | DuplicateKeyException e) {
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

  @SuppressWarnings({"unchecked", "rawtypes"})
  private EncodedPayload encodePayload(Object value) {
    ProcessPayloadCodec codec = payloadCodecs.forJavaType(value.getClass());
    return enforceSize(codec.encode(value));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private EncodedPayload encodeState(ProcessType type, StateSchemaVersion schema, Object state) {
    ProcessStateCodec codec = stateCodecs.forState(type, schema);
    return enforceSize(codec.encode(state));
  }

  /** Guard the configured {@code payload.max-bytes} cap at encode time. */
  private EncodedPayload enforceSize(EncodedPayload encoded) {
    int size = encoded.data().length;
    if (size > maxPayloadBytes) {
      throw new ProcessPayloadTooLargeException(
          encoded.type().logicalType(), size, maxPayloadBytes);
    }
    return encoded;
  }

  private Object decodeState(
      ProcessType type, StateSchemaVersion schema, String payloadType, byte[] payload) {
    ProcessStateCodec<?> codec = stateCodecs.forState(type, schema);
    return codec.decode(new EncodedPayload(new PayloadType(payloadType, schema.value()), payload));
  }
}
