package com.aipersimmon.ddd.processmanager.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.observability.ObservabilityAttributes;
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
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.effect.PublishIntegrationEvent;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.engine.observe.ProcessObserver;
import com.aipersimmon.ddd.processmanager.engine.store.ConcurrentTransitionException;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.engine.store.EffectStatus;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.engine.store.ParkedInputs;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineView;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectView;
import com.aipersimmon.ddd.processmanager.engine.store.RollingBackUnitOfWork;
import com.aipersimmon.ddd.processmanager.exception.ProcessAlreadyExistsException;
import com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException;
import com.aipersimmon.ddd.processmanager.exception.ProcessPayloadTooLargeException;
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the runtime promises for one advance: an input applies <em>exactly once</em>, and everything
 * a decision produces — the snapshot, the transition, the effects, the timers — either lands
 * together or not at all.
 *
 * <p>Both halves of that need a store that can say no. These tests run over in-memory stores that
 * keep the two refusals the promise is built on: the transition log rejects a second row under the
 * same input message id, and the instance snapshot rejects a write whose revision has moved on. A
 * double that accepted either would let every test here pass with the mechanism removed.
 *
 * <p>The races are posed with committed writes from a second transaction ({@code
 * committedElsewhere} / {@code advancedElsewhere}), because a conflict that a rollback also erases
 * is not a conflict — the retry would re-read its own starting point and simply succeed.
 */
class DefaultProcessRuntimeTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final ProcessType ORDERING = new ProcessType("Ordering");
  private static final ProcessBusinessKey ORDER_1 = new ProcessBusinessKey("order-1");
  private static final ProcessInstanceId INSTANCE = new ProcessInstanceId("instance-1");
  private static final ProcessRef REF = new ProcessRef(INSTANCE, ORDERING, ORDER_1);
  private static final ProcessStep AWAITING_PAYMENT = new ProcessStep("awaiting-payment");
  private static final ProcessStep SHIPPING = new ProcessStep("shipping");
  private static final PayloadType STATE_TYPE = new PayloadType("sample.state", 1);
  private static final PayloadType INPUT_TYPE = new PayloadType("sample.payload", 1);
  private static final PayloadType COMMAND_TYPE = new PayloadType("sample.command", 1);
  private static final PayloadType EVENT_TYPE = new PayloadType("sample.order-shipped", 1);
  private static final DeadlineName REVIEW = new DeadlineName("REVIEW");

  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final InMemoryProcessTransitionStore transitions =
      new InMemoryProcessTransitionStore(instances);
  private final InMemoryProcessEffectStore effects = new InMemoryProcessEffectStore();
  private final InMemoryProcessDeadlineStore deadlines = new InMemoryProcessDeadlineStore();
  private final RollingBackUnitOfWork unitOfWork =
      new RollingBackUnitOfWork(instances, transitions, effects, deadlines);
  private final CountingObserver observer = new CountingObserver();
  private final RecordingTracer tracer = new RecordingTracer();
  private final SequentialIds ids = new SequentialIds();

  // --- the process under test -------------------------------------------------------------------

  /** The business state; what is in it does not matter, only that it round-trips. */
  private record Order(String status) {}

  private record Say(String what) implements ProcessInput {}

  private record ReserveStock(String sku) implements Command<Void> {}

  @EventType(name = "sample.order-shipped", version = 1)
  private record OrderShipped(String orderId) implements IntegrationEvent {}

  /**
   * A definition whose two methods are supplied per test, so each test states the decision it is
   * about instead of threading business rules through a shared fixture. It counts its calls because
   * "the definition was not consulted again" is the observable form of exactly-once.
   */
  private static final class ScriptedDefinition implements ProcessDefinition<Order> {
    private final DefinitionVersion version;
    private final boolean active;
    private Reaction onStart = (state, input, context) -> running(SHIPPING);
    private Reaction onReact = (state, input, context) -> running(SHIPPING);
    private int startCalls;
    private int reactCalls;

    ScriptedDefinition() {
      this(DefinitionVersion.INITIAL, true);
    }

    ScriptedDefinition(DefinitionVersion version, boolean active) {
      this.version = version;
      this.active = active;
    }

    @Override
    public ProcessType processType() {
      return ORDERING;
    }

    @Override
    public DefinitionVersion definitionVersion() {
      return version;
    }

    @Override
    public boolean activeForNewInstances() {
      return active;
    }

    @Override
    public ProcessDecision<Order> start(ProcessInput input, ProcessContext context) {
      startCalls++;
      return onStart.decide(new Order("new"), input, context);
    }

    @Override
    public ProcessDecision<Order> react(Order state, ProcessInput input, ProcessContext context) {
      reactCalls++;
      return onReact.decide(state, input, context);
    }
  }

  @FunctionalInterface
  private interface Reaction {
    ProcessDecision<Order> decide(Order state, ProcessInput input, ProcessContext context);
  }

  private static ProcessDecision<Order> running(ProcessStep step, ProcessEffect... effects) {
    return new ProcessDecision<>(
        new Order("running"),
        ProcessLifecycle.RUNNING,
        step,
        Optional.empty(),
        new DecisionCode("carried-on"),
        List.of(effects));
  }

  private static ProcessDecision<Order> ended(
      ProcessLifecycle lifecycle, ProcessEffect... effects) {
    return new ProcessDecision<>(
        new Order("done"),
        lifecycle,
        new ProcessStep("done"),
        Optional.of(new ProcessOutcome("ORDER_CONFIRMED")),
        new DecisionCode("finished"),
        List.of(effects));
  }

  // --- codecs, ids, clock, observer, tracer -----------------------------------------------------

  /** Round-trips a value through its {@code toString}; the shape is irrelevant to these tests. */
  private abstract static class TextCodec<T> implements ProcessPayloadCodec<T> {
    private final PayloadType type;

    TextCodec(PayloadType type) {
      this.type = type;
    }

    @Override
    public PayloadType payloadType() {
      return type;
    }

    @Override
    public EncodedPayload encode(T value) {
      return new EncodedPayload(type, value.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private static final class SayCodec extends TextCodec<Say> {
    SayCodec() {
      super(INPUT_TYPE);
    }

    @Override
    public Class<Say> javaType() {
      return Say.class;
    }

    @Override
    public Say decode(EncodedPayload payload) {
      return new Say(new String(payload.data(), StandardCharsets.UTF_8));
    }
  }

  private static final class ReserveStockCodec extends TextCodec<ReserveStock> {
    ReserveStockCodec() {
      super(COMMAND_TYPE);
    }

    @Override
    public Class<ReserveStock> javaType() {
      return ReserveStock.class;
    }

    @Override
    public ReserveStock decode(EncodedPayload payload) {
      return new ReserveStock(new String(payload.data(), StandardCharsets.UTF_8));
    }
  }

  private static final class OrderShippedCodec extends TextCodec<OrderShipped> {
    OrderShippedCodec() {
      super(EVENT_TYPE);
    }

    @Override
    public Class<OrderShipped> javaType() {
      return OrderShipped.class;
    }

    @Override
    public OrderShipped decode(EncodedPayload payload) {
      return new OrderShipped(new String(payload.data(), StandardCharsets.UTF_8));
    }
  }

  private static final class OrderStateCodec implements ProcessStateCodec<Order> {
    @Override
    public ProcessType processType() {
      return ORDERING;
    }

    @Override
    public StateSchemaVersion schemaVersion() {
      return StateSchemaVersion.INITIAL;
    }

    @Override
    public EncodedPayload encode(Order state) {
      return new EncodedPayload(STATE_TYPE, state.status().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Order decode(EncodedPayload payload) {
      return new Order(new String(payload.data(), StandardCharsets.UTF_8));
    }
  }

  /** Ids in mint order, so a test can name the row the runtime is about to write. */
  private static final class SequentialIds implements java.util.function.Supplier<String> {
    private final List<String> minted = new ArrayList<>();

    @Override
    public String get() {
      String id = "id-" + (minted.size() + 1);
      minted.add(id);
      return id;
    }
  }

  private static final class CountingObserver implements ProcessObserver {
    private int conflictRetries;

    @Override
    public void advanceConflictRetry() {
      conflictRetries++;
    }

    @Override
    public void effectClaimed(int claimed, Duration latency) {}

    @Override
    public void effectDispatched(boolean success, Duration latency) {}
  }

  /** Keeps the one span the runtime opens, so its attributes can be read back. */
  private static final class RecordingTracer implements Tracer {
    private final List<String> names = new ArrayList<>();
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<Throwable> errors = new ArrayList<>();
    private int closed;

    @Override
    public SpanScope startSpan(String name) {
      names.add(name);
      return new SpanScope() {
        @Override
        public SpanScope attribute(String key, String value) {
          attributes.put(key, value);
          return this;
        }

        @Override
        public SpanScope attribute(String key, long value) {
          attributes.put(key, Long.toString(value));
          return this;
        }

        @Override
        public SpanScope error(Throwable error) {
          errors.add(error);
          return this;
        }

        @Override
        public void close() {
          closed++;
        }
      };
    }
  }

  private static final Clock CLOCK =
      new Clock() {
        @Override
        public Instant instant() {
          return NOW;
        }

        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }
      };

  // --- assembling the runtime -------------------------------------------------------------------

  private final ScriptedDefinition definition = new ScriptedDefinition();

  private DefaultProcessRuntime runtime() {
    return runtime(DuplicateBusinessKeyPolicy.REJECT, 3, Optional.empty(), definition);
  }

  private DefaultProcessRuntime runtime(
      DuplicateBusinessKeyPolicy policy,
      int maxRetries,
      Optional<Duration> maxLifetime,
      ProcessDefinition<?>... definitions) {
    return runtime(policy, maxRetries, maxLifetime, Long.MAX_VALUE, definitions);
  }

  private DefaultProcessRuntime runtime(
      DuplicateBusinessKeyPolicy policy,
      int maxRetries,
      Optional<Duration> maxLifetime,
      long maxPayloadBytes,
      ProcessDefinition<?>... definitions) {
    return new DefaultProcessRuntime(
        instances,
        transitions,
        effects,
        deadlines,
        new ProcessDefinitionRegistry(List.of(definitions)),
        new ProcessPayloadCodecRegistry(
            List.of(
                new SayCodec(),
                new ReserveStockCodec(),
                new OrderShippedCodec(),
                new MaxLifetimeExceededCodec())),
        new ProcessStateCodecRegistry(List.of(new OrderStateCodec())),
        unitOfWork,
        CLOCK,
        ids,
        policy,
        maxRetries,
        observer,
        maxLifetime,
        maxPayloadBytes,
        tracer);
  }

  private static CommandContext cause(String messageId) {
    return CommandContext.root(Tenants.of("acme"), messageId);
  }

  private InMemoryProcessTransitionStore.Row onlyTransition() {
    List<InMemoryProcessTransitionStore.Row> rows = transitions.rowsInOrder(INSTANCE);
    assertEquals(
        1, rows.size(), "expected exactly one transition, got " + transitions.transitionIds());
    return rows.get(0);
  }

  // --- start ------------------------------------------------------------------------------------

  @Test
  void aStartWritesTheInstanceItsFirstTransitionAndItsStagedEffects() {
    definition.onStart =
        (state, input, context) ->
            running(AWAITING_PAYMENT, new DispatchCommand(new ReserveStock("sku-1")));

    ProcessAdvanceResult result =
        runtime().start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    // Instance id then transition id, in that mint order — the effect id is derived from the
    // latter, which is what makes a redelivered effect keep one stable identity.
    assertEquals(new ProcessInstanceId("id-1"), result.processRef().instanceId());
    assertEquals("id-2", result.transitionId());
    assertEquals(new ProcessRevision(1), result.revision());
    assertFalse(result.duplicate());

    ProcessInstanceId instanceId = result.processRef().instanceId();
    assertEquals(ProcessLifecycle.RUNNING, instances.row(instanceId).lifecycle());
    assertEquals(AWAITING_PAYMENT, instances.row(instanceId).step());
    assertEquals(new ProcessRevision(1), instances.row(instanceId).revision());

    List<InMemoryProcessTransitionStore.Row> log = transitions.rowsInOrder(instanceId);
    assertEquals(1, log.size());
    assertEquals("START", log.get(0).transitionKind());
    assertEquals(Optional.empty(), log.get(0).fromLifecycle(), "a start comes from nowhere");
    assertEquals("m-1", log.get(0).inputMessageId());

    List<ProcessEffectView> staged = effects.byStatus(EffectStatus.PENDING, 10);
    assertEquals(1, staged.size());
    assertEquals("id-2#0", staged.get(0).effectId());
  }

  @Test
  void repeatingAStartUnderTheSameMessageIdIsANoOpThatReturnsTheOriginalTransition() {
    DefaultProcessRuntime runtime = runtime();
    ProcessAdvanceResult first = runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    ProcessAdvanceResult second = runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    assertTrue(second.duplicate());
    assertEquals(first.transitionId(), second.transitionId());
    assertEquals(1, definition.startCalls, "the decision must not be taken twice for one input");
    assertEquals(1, transitions.transitionIds().size());
  }

  @Test
  void aSecondStartOnALiveBusinessKeyIsRefusedWhenThePolicySaysReject() {
    DefaultProcessRuntime runtime = runtime();
    runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    // A different message id, so process-level dedup cannot answer it — this is the business
    // question of whether one key may have two instances, and REJECT says no.
    ProcessAlreadyExistsException refused =
        assertThrows(
            ProcessAlreadyExistsException.class,
            () -> runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-2")));
    assertEquals(ORDER_1, refused.businessKey());
  }

  @Test
  void aSecondStartOnALiveBusinessKeyFoldsIntoItWhenThePolicySaysFold() {
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.FOLD, 3, Optional.empty(), definition);
    ProcessAdvanceResult first = runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    ProcessAdvanceResult second = runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-2"));

    // For a transport that re-mints message ids, a repeated start is a redelivery rather than a
    // second order, so it folds into the running instance at its latest transition.
    assertTrue(second.duplicate());
    assertEquals(first.transitionId(), second.transitionId());
    assertEquals(1, definition.startCalls);
  }

  @Test
  void anotherTenantsInstanceOnTheSameBusinessKeyDoesNotBlockAStart() {
    DefaultProcessRuntime runtime = runtime();
    runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    ProcessAdvanceResult other =
        runtime.start(
            ORDERING, ORDER_1, new Say("place"), CommandContext.root(Tenants.of("globex"), "m-2"));

    // The key is (tenant, type, businessKey). An unscoped lookup would not merely refuse this
    // start — it would load, and lock, another tenant's instance.
    assertFalse(other.duplicate());
    assertEquals(2, definition.startCalls);
  }

  @Test
  void theMaxLifetimeBackstopIsArmedOnAStartThatLeavesTheInstanceRunning() {
    // An ordinary effect alongside it: only the two deadline effects say anything about who owns
    // the reserved timer, and everything else must leave the backstop alone.
    definition.onStart =
        (state, input, context) ->
            running(AWAITING_PAYMENT, new DispatchCommand(new ReserveStock("sku-1")));
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.REJECT, 3, Optional.of(Duration.ofHours(2)), definition);

    runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    List<ProcessDeadlineView> armed = deadlines.byStatus(DeadlineStatus.PENDING, 10);
    assertEquals(1, armed.size());
    assertEquals(MaxLifetimeExceeded.DEADLINE_NAME.value(), armed.get(0).name());
    assertEquals(NOW.plus(Duration.ofHours(2)), armed.get(0).dueAt());
  }

  @Test
  void aDefinitionThatArmsTheReservedNameItselfKeepsItsOwnTimerRatherThanTheBackstop() {
    definition.onStart =
        (state, input, context) ->
            running(
                AWAITING_PAYMENT,
                new ScheduleDeadline(
                    MaxLifetimeExceeded.DEADLINE_NAME,
                    NOW.plus(Duration.ofMinutes(30)),
                    new MaxLifetimeExceeded()));
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.REJECT, 3, Optional.of(Duration.ofHours(2)), definition);

    runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    // The backstop steps aside instead of clobbering the definition's timer with a higher
    // generation, which would silently replace a deliberate TTL with the configured default.
    List<ProcessDeadlineView> armed = deadlines.byStatus(DeadlineStatus.PENDING, 10);
    assertEquals(1, armed.size());
    assertEquals(NOW.plus(Duration.ofMinutes(30)), armed.get(0).dueAt());
  }

  @Test
  void aDefinitionThatCancelsTheReservedNameOnStartGetsNoBackstopEither() {
    definition.onStart =
        (state, input, context) ->
            running(AWAITING_PAYMENT, new CancelDeadline(MaxLifetimeExceeded.DEADLINE_NAME));
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.REJECT, 3, Optional.of(Duration.ofHours(2)), definition);

    runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    assertEquals(
        List.of(),
        deadlines.byStatus(DeadlineStatus.PENDING, 10),
        "cancelling the reserved name is a definition saying it wants no lifetime cap");
  }

  @Test
  void aTerminalDecisionStillStagesTheEventThatAnnouncesTheEnding() {
    definition.onStart =
        (state, input, context) ->
            ended(ProcessLifecycle.COMPLETED, new PublishIntegrationEvent(new OrderShipped("o-1")));

    runtime().start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    // Only the timers are cancelled when a process ends. The final event of a flow is exactly the
    // kind of effect a terminal decision produces, so dropping it would silence the ending.
    assertEquals(1, effects.byStatus(EffectStatus.PENDING, 10).size());
    assertEquals(
        ProcessEffectKind.PUBLISH_INTEGRATION_EVENT.name(),
        effects.byStatus(EffectStatus.PENDING, 10).get(0).kind());
  }

  @Test
  void aStartThatEndsTheProcessImmediatelyArmsNoBackstop() {
    definition.onStart = (state, input, context) -> ended(ProcessLifecycle.COMPLETED);
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.REJECT, 3, Optional.of(Duration.ofHours(2)), definition);

    runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1"));

    // A timer on an ended instance can never fire, and a due PENDING row nobody will ever claim
    // keeps the backlog signal degraded forever.
    assertEquals(List.of(), deadlines.byStatus(DeadlineStatus.PENDING, 10));
  }

  @Test
  void aTerminalDecisionThatStillSchedulesADeadlineIsRefusedRatherThanQuietlyDropped() {
    definition.onStart =
        (state, input, context) ->
            ended(
                ProcessLifecycle.COMPLETED,
                new ScheduleDeadline(REVIEW, NOW.plusSeconds(60), new Say("review")));
    DefaultProcessRuntime runtime = runtime();

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1")));

    // Discarding it silently would look exactly like a timeout that never arrived.
    assertTrue(refused.getMessage().contains("can never fire"), refused.getMessage());
  }

  // --- handle -----------------------------------------------------------------------------------

  @Test
  void anAdvanceMovesTheSnapshotAppendsATransitionAndStagesItsEffects() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    definition.onReact =
        (state, input, context) -> running(SHIPPING, new DispatchCommand(new ReserveStock("s")));

    ProcessAdvanceResult result = runtime().handle(REF, new Say("paid"), cause("m-1"));

    assertEquals(new ProcessRevision(1), result.revision());
    assertEquals(SHIPPING, instances.row(INSTANCE).step());
    assertEquals("ADVANCE", onlyTransition().transitionKind());
    assertEquals(
        Optional.of(ProcessLifecycle.RUNNING),
        onlyTransition().fromLifecycle(),
        "the log records the move, not just where it landed");
    assertEquals(1, effects.byStatus(EffectStatus.PENDING, 10).size());
  }

  @Test
  void repeatingAnInputIsANoOpAndTheDefinitionIsNotConsultedAgain() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    definition.onReact =
        (state, input, context) -> running(SHIPPING, new DispatchCommand(new ReserveStock("s")));
    DefaultProcessRuntime runtime = runtime();
    ProcessAdvanceResult first = runtime.handle(REF, new Say("paid"), cause("m-1"));

    ProcessAdvanceResult second = runtime.handle(REF, new Say("paid"), cause("m-1"));

    // At-least-once delivery upstream means this happens routinely; the effect of the input must
    // not be applied twice, and the second call must report the first one's outcome.
    assertTrue(second.duplicate());
    assertEquals(first.transitionId(), second.transitionId());
    assertEquals(1, definition.reactCalls);
    assertEquals(new ProcessRevision(1), instances.row(INSTANCE).revision());
    assertEquals(
        1,
        effects.byStatus(EffectStatus.PENDING, 10).size(),
        "and the command it staged is not sent a second time");
  }

  @Test
  void handlingAnInstanceThatDoesNotExistFails() {
    DefaultProcessRuntime runtime = runtime();

    assertThrows(
        ProcessNotFoundException.class, () -> runtime.handle(REF, new Say("paid"), cause("m-1")));
  }

  @Test
  void aRefThatNamesADifferentProcessThanTheStoredInstanceIsRefused() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    ProcessRef wrong = new ProcessRef(INSTANCE, new ProcessType("Shipping"), ORDER_1);
    DefaultProcessRuntime runtime = runtime();

    assertThrows(
        IllegalArgumentException.class, () -> runtime.handle(wrong, new Say("paid"), cause("m-1")));
    assertEquals(0, definition.reactCalls);
  }

  @Test
  void anIllegalLifecycleMoveIsRefusedEvenThoughTheDecisionItselfIsWellFormed() {
    instances.given(INSTANCE, ProcessLifecycle.COMPENSATING);
    definition.onReact = (state, input, context) -> running(SHIPPING);
    DefaultProcessRuntime runtime = runtime();

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class, () -> runtime.handle(REF, new Say("paid"), cause("m-1")));

    // A process that has begun compensating does not go back to running; the decision record
    // cannot check this because only the runtime knows where the instance is now.
    assertTrue(refused.getMessage().contains("COMPENSATING -> RUNNING"), refused.getMessage());
    assertEquals(List.of(), transitions.transitionIds(), "and nothing was written");
  }

  @Test
  void aRunningInstanceKeepsThePinnedVersionEvenAfterANewerOneBecomesActive() {
    ScriptedDefinition v1 = new ScriptedDefinition(DefinitionVersion.INITIAL, false);
    ScriptedDefinition v2 = new ScriptedDefinition(new DefinitionVersion("v2"), true);
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);

    runtime(DuplicateBusinessKeyPolicy.REJECT, 3, Optional.empty(), v1, v2)
        .handle(REF, new Say("paid"), cause("m-1"));

    // Rolling out v2 must not re-route instances mid-flight onto a definition that never saw
    // their earlier steps.
    assertEquals(1, v1.reactCalls);
    assertEquals(0, v2.reactCalls);
    assertEquals(DefinitionVersion.INITIAL, instances.row(INSTANCE).definitionVersion());
  }

  @Test
  void aPayloadTooLargeForTheConfiguredCapIsRefusedAtEncodeTime() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.REJECT, 3, Optional.empty(), 4L, definition);

    assertThrows(
        ProcessPayloadTooLargeException.class,
        () -> runtime.handle(REF, new Say("a payload well past four bytes"), cause("m-1")));
  }

  // --- suspended instances: park rather than reject ----------------------------------------------

  @Test
  void anInputToASuspendedInstanceIsParkedRatherThanRejected() {
    instances.given(INSTANCE, ProcessLifecycle.SUSPENDED);

    ProcessAdvanceResult result = runtime().handle(REF, new Say("paid"), cause("m-1"));

    // Rebounding to the message layer would either drop the input or spin the transport; parking
    // it as an audit transition lets the transport ack and keeps the input for replay on resume.
    assertEquals(0, definition.reactCalls);
    assertFalse(result.duplicate());
    assertEquals("PARKED", onlyTransition().transitionKind());
    assertEquals(ProcessLifecycle.SUSPENDED, result.lifecycle());
    assertEquals(AWAITING_PAYMENT, result.step(), "parking moves nothing");
    assertEquals(1, transitions.findUnreplayedParkedInputs(INSTANCE).size());
  }

  @Test
  void aReplayThatLosesTheRaceWithAFreshSuspensionIsNotParkedASecondTime() {
    instances.given(INSTANCE, ProcessLifecycle.SUSPENDED);
    DefaultProcessRuntime runtime = runtime();
    ProcessAdvanceResult parked = runtime.handle(REF, new Say("paid"), cause("m-1"));

    ProcessAdvanceResult replay =
        runtime.handle(REF, new Say("paid"), cause(ParkedInputs.replayIdFor("m-1")));

    // Parking the replay would both duplicate a debt that is still owed and start a
    // 'parked:parked:…' chain that eventually overflows the id column. Returning SUSPENDED is what
    // tells the drain worker to leave the debt standing and stop.
    assertEquals(parked.transitionId(), replay.transitionId());
    assertTrue(replay.duplicate());
    assertEquals(1, transitions.transitionIds().size());
    assertEquals(1, transitions.findUnreplayedParkedInputs(INSTANCE).size());
  }

  @Test
  void aReplayWithNoParkedInputBehindItIsAProgrammingErrorRatherThanASecondPark() {
    instances.given(INSTANCE, ProcessLifecycle.SUSPENDED);
    DefaultProcessRuntime runtime = runtime();

    IllegalStateException broken =
        assertThrows(
            IllegalStateException.class,
            () ->
                runtime.handle(
                    REF, new Say("paid"), cause(ParkedInputs.replayIdFor("never-parked"))));

    assertTrue(broken.getMessage().contains("has no parked input"), broken.getMessage());
  }

  @Test
  void anInputToAnEndedInstanceIsANoOpAtItsLastTransition() {
    instances.given(INSTANCE, ProcessLifecycle.COMPLETED);
    transitions.append(
        transitions.entry(
            "t-last", INSTANCE, "m-0", ProcessLifecycle.COMPLETED, AWAITING_PAYMENT, "ADVANCE"),
        NOW);

    ProcessAdvanceResult result = runtime().handle(REF, new Say("late"), cause("m-1"));

    // A late arrival for a finished process is not an error — the sender could not have known —
    // but it must not reopen it either.
    assertTrue(result.duplicate());
    assertEquals("t-last", result.transitionId());
    assertEquals(0, definition.reactCalls);
  }

  // --- conflicts ---------------------------------------------------------------------------------

  @Test
  void anAdvanceThatLosesTheRevisionRaceIsRetriedAgainstTheWinnersState() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    definition.onReact =
        (state, input, context) -> {
          if (definition.reactCalls == 1) {
            // Another transaction committed a transition while this one was deciding.
            instances.advancedElsewhere(INSTANCE, NOW);
          }
          return running(SHIPPING);
        };

    ProcessAdvanceResult result = runtime().handle(REF, new Say("paid"), cause("m-1"));

    assertEquals(2, definition.reactCalls, "the decision is retaken against what actually landed");
    assertEquals(new ProcessRevision(2), result.revision());
    assertFalse(result.duplicate());
    assertEquals(1, observer.conflictRetries);
    assertEquals(1, transitions.transitionIds().size(), "the losing attempt left nothing behind");
  }

  @Test
  void anAdvanceWhoseInputWasRecordedByTheWinnerFoldsIntoItAsADuplicate() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    definition.onReact =
        (state, input, context) -> {
          if (definition.reactCalls == 1) {
            transitions.committedElsewhere(
                transitions.entry(
                    "t-winner",
                    INSTANCE,
                    context.cause().messageId(),
                    ProcessLifecycle.RUNNING,
                    SHIPPING,
                    "ADVANCE"),
                NOW);
          }
          return running(SHIPPING);
        };

    ProcessAdvanceResult result = runtime().handle(REF, new Say("paid"), cause("m-1"));

    // The unique dedup key is what makes the race safe rather than merely unlikely: the loser
    // cannot append a second row for the input, and on retry it reports the winner's transition.
    assertTrue(result.duplicate());
    assertEquals("t-winner", result.transitionId());
    assertEquals(1, definition.reactCalls, "the retry short-circuits before deciding again");
    assertEquals(1, observer.conflictRetries);
    assertEquals(
        new ProcessRevision(0),
        instances.row(INSTANCE).revision(),
        "and the losing attempt's snapshot write was rolled back with it");
  }

  @Test
  void aConflictInsideSomebodyElsesTransactionIsNotRetried() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    definition.onReact =
        (state, input, context) -> {
          transitions.committedElsewhere(
              transitions.entry(
                  "t-winner-" + definition.reactCalls,
                  INSTANCE,
                  context.cause().messageId(),
                  ProcessLifecycle.RUNNING,
                  SHIPPING,
                  "ADVANCE"),
              NOW);
          return running(SHIPPING);
        };
    DefaultProcessRuntime runtime = runtime();

    assertThrows(
        ConcurrentTransitionException.class,
        () -> unitOfWork.execute(() -> runtime.handle(REF, new Say("paid"), cause("m-1"))));

    // The first attempt's failure has already doomed the caller's transaction, so a second attempt
    // could only fail again — and would report a fresh conflict in place of the original cause.
    // The caller's own rollback and the transport's redelivery are the retry.
    assertEquals(1, definition.reactCalls);
    assertEquals(0, observer.conflictRetries);
  }

  @Test
  void anAdvanceThatJoinedACallersTransactionIsRolledBackWithIt() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    definition.onReact =
        (state, input, context) -> running(SHIPPING, new DispatchCommand(new ReserveStock("s")));
    DefaultProcessRuntime runtime = runtime();

    assertThrows(
        IllegalStateException.class,
        () ->
            unitOfWork.execute(
                () -> {
                  runtime.handle(REF, new Say("paid"), cause("m-1"));
                  throw new IllegalStateException("the caller failed after advancing");
                }));

    // The composition this runtime advertises: an advance inside a command handler's or an Inbox
    // listener's transaction is part of it, so a caller that fails afterwards takes the whole
    // advance back — snapshot, transition and staged effects together.
    assertEquals(new ProcessRevision(0), instances.row(INSTANCE).revision());
    assertEquals(List.of(), transitions.transitionIds());
    assertEquals(List.of(), effects.byStatus(EffectStatus.PENDING, 10));
  }

  @Test
  void aConflictThatOutlastsTheRetryBudgetIsRaisedRatherThanRetriedForever() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    definition.onReact =
        (state, input, context) -> {
          instances.advancedElsewhere(INSTANCE, NOW);
          return running(SHIPPING);
        };
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.REJECT, 1, Optional.empty(), definition);

    StaleProcessRevisionException lost =
        assertThrows(
            StaleProcessRevisionException.class,
            () -> runtime.handle(REF, new Say("paid"), cause("m-1")));

    assertEquals(2, definition.reactCalls, "one attempt plus the configured retry");
    assertEquals(2, observer.conflictRetries);
    assertEquals(new ProcessRevision(2), lost.actual(), "and it says what it lost to");
  }

  // --- corrupt data ------------------------------------------------------------------------------

  @Test
  void anInstanceWithNoTransitionLogIsSaidToBeBrokenRatherThanAnsweredAbout() {
    // Only reachable if a row was written outside the runtime, which is why it is worth a signal:
    // every path that folds into an existing instance answers with a transition id, and there is
    // no honest id to give.
    instances.given(INSTANCE, ProcessLifecycle.COMPLETED);
    DefaultProcessRuntime runtime = runtime();

    IllegalStateException broken =
        assertThrows(
            IllegalStateException.class, () -> runtime.handle(REF, new Say("late"), cause("m-1")));

    assertEquals("instance without any transition", broken.getMessage());
  }

  @Test
  void aFoldingStartOntoAnInstanceWithNoTransitionLogSaysSoToo() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    DefaultProcessRuntime runtime =
        runtime(DuplicateBusinessKeyPolicy.FOLD, 3, Optional.empty(), definition);

    IllegalStateException broken =
        assertThrows(
            IllegalStateException.class,
            () -> runtime.start(ORDERING, ORDER_1, new Say("place"), cause("m-1")));

    assertEquals("instance without any transition", broken.getMessage());
  }

  // --- construction ------------------------------------------------------------------------------

  @Test
  void theShortestConstructorIsTheSameRuntimeWithoutTheOptionalSeams() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    DefaultProcessRuntime runtime =
        new DefaultProcessRuntime(
            instances,
            transitions,
            effects,
            deadlines,
            new ProcessDefinitionRegistry(List.of(definition)),
            new ProcessPayloadCodecRegistry(List.of(new SayCodec(), new ReserveStockCodec())),
            new ProcessStateCodecRegistry(List.of(new OrderStateCodec())),
            unitOfWork,
            CLOCK,
            ids,
            DuplicateBusinessKeyPolicy.REJECT,
            3);

    ProcessAdvanceResult result = runtime.handle(REF, new Say("paid"), cause("m-1"));

    // Observer, max-lifetime, payload cap and tracer are all opt-in; without them an advance still
    // does the whole job rather than quietly skipping part of it.
    assertEquals(new ProcessRevision(1), result.revision());
    assertEquals(SHIPPING, instances.row(INSTANCE).step());
    assertEquals(List.of(), deadlines.byStatus(DeadlineStatus.PENDING, 10));
    assertEquals(List.of(), tracer.names, "and it opened no span, because it was given no tracer");
  }

  // --- tracing -----------------------------------------------------------------------------------

  @Test
  void theAdvanceSpanNamesTheDecisionItWrapped() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);

    runtime().handle(REF, new Say("paid"), cause("m-1"));

    // Driven by a relay or a deadline worker there is no command span above this, so without it an
    // advance is invisible in a trace.
    assertEquals(List.of("process.advance Ordering"), tracer.names);
    assertEquals("Ordering", tracer.attributes.get(ObservabilityAttributes.PROCESS_TYPE));
    assertEquals("order-1", tracer.attributes.get(ObservabilityAttributes.PROCESS_BUSINESS_KEY));
    assertEquals("instance-1", tracer.attributes.get(ObservabilityAttributes.PROCESS_INSTANCE_ID));
    assertEquals("RUNNING", tracer.attributes.get(ObservabilityAttributes.LIFECYCLE));
    assertEquals("shipping", tracer.attributes.get(ObservabilityAttributes.STEP));
    assertEquals(1, tracer.closed);
  }

  @Test
  void aFailedAdvanceMarksItsSpanAndStillClosesIt() {
    DefaultProcessRuntime runtime = runtime();

    assertThrows(
        ProcessNotFoundException.class, () -> runtime.handle(REF, new Say("paid"), cause("m-1")));

    assertEquals(1, tracer.errors.size());
    assertTrue(tracer.errors.get(0) instanceof ProcessNotFoundException);
    assertEquals(
        1, tracer.closed, "a span left open would leak the context of every later advance");
  }
}
