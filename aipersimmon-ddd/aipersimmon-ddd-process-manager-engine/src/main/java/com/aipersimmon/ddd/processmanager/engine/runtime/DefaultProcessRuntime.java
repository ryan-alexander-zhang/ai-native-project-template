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
import com.aipersimmon.ddd.processmanager.engine.store.ParkedInputs;
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
 * revision guard on the snapshot update, with a bounded retry when this runtime owns the
 * transaction and a propagated conflict when it joined the caller's (see {@code withRetry}).
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

  @Override
  public ProcessAdvanceResult handle(
      ProcessType processType,
      ProcessBusinessKey businessKey,
      ProcessInput input,
      CommandContext cause) {
    // A plain read, not FOR UPDATE: the by-ref handle below re-loads under its own lock, so
    // locking here would only widen the window without buying anything. Scoped to the advancing
    // tenant for the same reason doStart's lookup is — business keys are tenant-relative.
    ProcessRef ref =
        instances
            .readByBusinessKey(cause.tenantId().value(), processType, businessKey)
            .map(ProcessInstanceRow::ref)
            .orElseThrow(() -> new ProcessNotFoundException(processType, businessKey));
    return handle(ref, input, cause);
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

    // Scope the lookup to the advancing tenant: the (tenant_id, process_type, business_key) key
    // lets
    // two tenants reuse a business key, so an unscoped lookup could load — and FOR UPDATE lock —
    // another tenant's instance.
    Optional<ProcessInstanceRow> existing =
        instances.findByBusinessKey(cause.tenantId().value(), processType, businessKey);
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
            cause.tenantId().value(),
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
      if (ParkedInputs.isReplayId(cause.messageId())) {
        // A replay that lost the race with a fresh suspension. Park nothing: the input's park row
        // still exists and is still owed a replay, so recording a second one would both duplicate
        // the debt and start a 'parked:parked:…' chain that eventually overflows the id column.
        // Returning the instance's SUSPENDED lifecycle is what tells the parked-input worker to
        // leave the debt standing and stop draining this instance.
        String parked =
            transitions
                .findTransitionIdByInput(
                    ref.instanceId(), ParkedInputs.originalIdOf(cause.messageId()))
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "replay "
                                + cause.messageId()
                                + " has no parked input on instance "
                                + ref.instanceId().value()));
        return duplicateResult(row, parked);
      }
      // Do not rebound to the message layer: park the input as an audit transition (deduped by
      // the UNIQUE input message id) and return, so the transport can ack. It is replayed in
      // arrival order once the instance resumes and the parked-input worker drains the queue.
      String parkedId = idGenerator.get();
      Instant parkedAt = clock.instant();
      EncodedPayload parkedInput = serdes.encodePayload(input);
      transitions.append(
          new ProcessTransitionInsert(
              cause.tenantId().value(),
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
            row.tenantId(),
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

  /**
   * Run one advance, retrying a concurrency conflict a bounded number of times — but only when this
   * advance owns its transaction.
   *
   * <p>When the advance joins a caller's transaction (a command handler or an Inbox listener, the
   * composition this runtime advertises), the first attempt's rollback has already doomed that
   * shared transaction: Spring marks it rollback-only, and a {@code ConcurrentTransitionException}
   * comes from a unique-key violation that leaves the transaction aborted on PostgreSQL. A second
   * attempt could therefore only fail — and, worse, would report a fresh conflict in place of the
   * original cause. So the conflict propagates to the caller, whose own rollback and the
   * transport's redelivery of the input are the retry. Retrying inside the transaction we own is
   * sound because only the attempt is rolled back.
   */
  private ProcessAdvanceResult withRetry(Supplier<ProcessAdvanceResult> attempt) {
    if (unitOfWork.inExistingTransaction()) {
      return attempt.get();
    }
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
