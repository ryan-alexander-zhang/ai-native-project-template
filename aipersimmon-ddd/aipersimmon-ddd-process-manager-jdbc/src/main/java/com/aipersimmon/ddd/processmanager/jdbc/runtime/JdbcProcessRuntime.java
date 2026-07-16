package com.aipersimmon.ddd.processmanager.jdbc.runtime;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
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
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
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
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;

/**
 * The production {@link ProcessRuntime} over the four-table JDBC store. Each
 * {@code start}/{@code handle} runs the pure definition and atomically persists the new
 * snapshot, the appended transition, the staged effects, and the deadline changes in
 * one {@code REQUIRED} transaction (design-00004 §4.3); a relay delivers effects
 * afterwards. Effects are staged with their durable identity — {@code messageId} equal
 * to a deterministic {@code effectId} of {@code transitionId#index} — so at-least-once
 * redelivery keeps one stable id (decision-00016).
 *
 * <p>Process-level idempotency comes from the {@code UNIQUE(instance_id, input_message_id)}
 * constraint (a repeated input is a duplicate no-op); optimistic concurrency comes from
 * the revision guard on the snapshot update, with a bounded retry.
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
    }

    @Override
    public ProcessAdvanceResult start(
            ProcessType processType, ProcessBusinessKey businessKey, ProcessInput input, CommandContext cause) {
        return withRetry(() -> unitOfWork.execute(() -> doStart(processType, businessKey, input, cause)));
    }

    @Override
    public ProcessAdvanceResult handle(ProcessRef processRef, ProcessInput input, CommandContext cause) {
        return withRetry(() -> unitOfWork.execute(() -> doHandle(processRef, input, cause)));
    }

    private ProcessAdvanceResult doStart(
            ProcessType processType, ProcessBusinessKey businessKey, ProcessInput input, CommandContext cause) {
        ProcessDefinition<?> definition = definitions.resolveActive(processType);

        Optional<ProcessInstanceRow> existing = instances.findByBusinessKey(processType, businessKey);
        if (existing.isPresent()) {
            return resolveExistingStart(existing.get(), processType, businessKey, cause);
        }

        ProcessInstanceId instanceId = new ProcessInstanceId(idGenerator.get());
        ProcessRef ref = new ProcessRef(instanceId, processType, businessKey);
        Instant now = clock.instant();
        ProcessContext context = new ProcessContext(
                ref, ProcessRevision.initial(), definition.definitionVersion(),
                Optional.empty(), Optional.empty(), now, cause);

        ProcessDecision<Object> decision = callStart(definition, input, context);
        ProcessRevision revision = ProcessRevision.initial().next();
        String transitionId = idGenerator.get();

        EncodedPayload state = encodeState(processType, definition.stateSchemaVersion(), decision.state());
        instances.insert(new ProcessInstanceRow(
                ref, definition.definitionVersion(), definition.stateSchemaVersion(),
                decision.lifecycle(), decision.step(), decision.outcome(), revision,
                state.type().logicalType(), state.data(), Optional.empty(), Optional.empty()), now);

        appendTransition(ref, transitionId, cause, input, Optional.empty(), Optional.empty(), decision, "START", now);
        stageEffects(ref, transitionId, decision, cause, now);

        return new ProcessAdvanceResult(
                ref, revision, decision.lifecycle(), decision.step(), false, transitionId);
    }

    private ProcessAdvanceResult resolveExistingStart(
            ProcessInstanceRow row, ProcessType processType, ProcessBusinessKey businessKey, CommandContext cause) {
        Optional<String> duplicate =
                transitions.findTransitionIdByInput(row.ref().instanceId(), cause.messageId());
        if (duplicate.isPresent()) {
            return duplicateResult(row, duplicate.get());
        }
        if (duplicatePolicy == DuplicateBusinessKeyPolicy.REJECT) {
            throw new ProcessAlreadyExistsException(processType, businessKey);
        }
        String latest = transitions.findLatestTransitionId(row.ref().instanceId())
                .orElseThrow(() -> new IllegalStateException("instance without any transition"));
        return duplicateResult(row, latest);
    }

    private ProcessAdvanceResult doHandle(ProcessRef ref, ProcessInput input, CommandContext cause) {
        ProcessInstanceRow row = instances.findForUpdate(ref.instanceId())
                .orElseThrow(() -> new ProcessNotFoundException(ref));

        Optional<String> duplicate = transitions.findTransitionIdByInput(ref.instanceId(), cause.messageId());
        if (duplicate.isPresent()) {
            return duplicateResult(row, duplicate.get());
        }
        if (row.lifecycle() == ProcessLifecycle.SUSPENDED) {
            // Do not rebound to the message layer: park the input as an audit transition (deduped by
            // the UNIQUE input message id) and return, so the transport can ack. It is replayed in
            // arrival order when the instance resumes (design-00004 §4.6).
            String parkedId = idGenerator.get();
            Instant parkedAt = clock.instant();
            EncodedPayload parkedInput = encodePayload(input);
            transitions.append(new ProcessTransitionInsert(
                    parkedId, ref.instanceId(), cause.messageId(),
                    parkedInput.type().logicalType(), parkedInput.type().version(), parkedInput.data(),
                    Optional.of(row.lifecycle()), row.lifecycle(), Optional.of(row.step()), row.step(),
                    new DecisionCode("parked"), "PARKED"), parkedAt);
            return new ProcessAdvanceResult(
                    ref, row.revision(), row.lifecycle(), row.step(), false, parkedId);
        }
        if (row.lifecycle().isTerminal()) {
            String latest = transitions.findLatestTransitionId(ref.instanceId())
                    .orElseThrow(() -> new IllegalStateException("instance without any transition"));
            return duplicateResult(row, latest);
        }

        ProcessDefinition<?> definition = definitions.resolve(ref.processType(), row.definitionVersion());
        Object state = decodeState(ref.processType(), row.stateSchemaVersion(),
                row.statePayloadType(), row.statePayload());
        Instant now = clock.instant();
        ProcessContext context = new ProcessContext(
                ref, row.revision(), definition.definitionVersion(),
                Optional.of(row.lifecycle()), Optional.of(row.step()), now, cause);

        ProcessDecision<Object> decision = callReact(definition, state, input, context);
        if (!row.lifecycle().canTransitionTo(decision.lifecycle())) {
            throw new IllegalStateException(
                    "illegal lifecycle transition " + row.lifecycle() + " -> " + decision.lifecycle()
                            + " for instance " + ref.instanceId().value());
        }

        ProcessRevision revision = row.revision().next();
        String transitionId = idGenerator.get();
        EncodedPayload state2 = encodeState(ref.processType(), definition.stateSchemaVersion(), decision.state());
        ProcessInstanceRow updated = new ProcessInstanceRow(
                ref, definition.definitionVersion(), definition.stateSchemaVersion(),
                decision.lifecycle(), decision.step(), decision.outcome(), revision,
                state2.type().logicalType(), state2.data(), Optional.empty(), Optional.empty());

        int rows = instances.updateSnapshot(updated, row.revision(), now);
        if (rows == 0) {
            ProcessRevision actual = instances.find(ref.instanceId())
                    .map(ProcessInstanceRow::revision).orElse(row.revision());
            throw new StaleProcessRevisionException(ref, row.revision(), actual);
        }

        appendTransition(ref, transitionId, cause, input,
                Optional.of(row.lifecycle()), Optional.of(row.step()), decision, "ADVANCE", now);
        stageEffects(ref, transitionId, decision, cause, now);

        return new ProcessAdvanceResult(
                ref, revision, decision.lifecycle(), decision.step(), false, transitionId);
    }

    private void appendTransition(
            ProcessRef ref, String transitionId, CommandContext cause, ProcessInput input,
            Optional<ProcessLifecycle> fromLifecycle, Optional<com.aipersimmon.ddd.processmanager.model.ProcessStep> fromStep,
            ProcessDecision<Object> decision, String kind, Instant now) {
        EncodedPayload encodedInput = encodePayload(input);
        transitions.append(new ProcessTransitionInsert(
                transitionId, ref.instanceId(), cause.messageId(),
                encodedInput.type().logicalType(), encodedInput.type().version(), encodedInput.data(),
                fromLifecycle, decision.lifecycle(), fromStep, decision.step(),
                decision.decisionCode(), kind), now);
    }

    private void stageEffects(
            ProcessRef ref, String transitionId, ProcessDecision<Object> decision, CommandContext cause, Instant now) {
        int index = 0;
        for (ProcessEffect effect : decision.effects()) {
            switch (effect) {
                case DispatchCommand dispatch ->
                        stageMessageEffect(ref, transitionId, index, dispatch, encodePayload(dispatch.command()), cause, now);
                case PublishIntegrationEvent publish ->
                        stageMessageEffect(ref, transitionId, index, publish, encodePayload(publish.event()), cause, now);
                case ScheduleDeadline schedule -> scheduleDeadline(ref, schedule, now);
                case CancelDeadline cancel -> deadlines.cancelCurrent(ref.instanceId(), cancel.name(), now);
            }
            index++;
        }
    }

    private void stageMessageEffect(
            ProcessRef ref, String transitionId, int index, ProcessEffect effect,
            EncodedPayload payload, CommandContext cause, Instant now) {
        String effectId = transitionId + "#" + index;
        effects.insert(new ProcessEffectInsert(
                effectId, ref.instanceId(), transitionId, index, effect.kind(),
                payload.type().logicalType(), payload.type().version(), payload.data(),
                effectId, cause.correlationId(), cause.messageId(), cause.traceId()), now);
    }

    private void scheduleDeadline(ProcessRef ref, ScheduleDeadline schedule, Instant now) {
        long generation = deadlines.nextGeneration(ref.instanceId(), schedule.name());
        EncodedPayload input = encodePayload(schedule.input());
        deadlines.schedule(new ProcessDeadlineInsert(
                idGenerator.get(), ref.instanceId(), schedule.name(), generation, schedule.dueAt(),
                input.type().logicalType(), input.type().version(), input.data()), now);
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
            }
        }
        throw last;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessDecision<Object> callStart(ProcessDefinition<?> definition, ProcessInput input, ProcessContext ctx) {
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
        return codec.encode(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EncodedPayload encodeState(ProcessType type, StateSchemaVersion schema, Object state) {
        ProcessStateCodec codec = stateCodecs.forState(type, schema);
        return codec.encode(state);
    }

    private Object decodeState(
            ProcessType type, StateSchemaVersion schema, String payloadType, byte[] payload) {
        ProcessStateCodec<?> codec = stateCodecs.forState(type, schema);
        return codec.decode(new EncodedPayload(new PayloadType(payloadType, schema.value()), payload));
    }
}
