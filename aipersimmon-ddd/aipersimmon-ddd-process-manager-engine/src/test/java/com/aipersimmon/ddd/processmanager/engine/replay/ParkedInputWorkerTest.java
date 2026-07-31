package com.aipersimmon.ddd.processmanager.engine.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.engine.store.ParkedInputs;
import com.aipersimmon.ddd.processmanager.engine.store.RollingBackUnitOfWork;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The durable replay queue, drained.
 *
 * <p>Parking is what keeps a suspended instance from bouncing inputs back at the message layer, and
 * the debt it creates is owed exactly once per parked row. Everything here is about that debt: it
 * is settled only after the replay's own transaction committed, it is settled in arrival order, and
 * a replay that could not be applied leaves it standing rather than quietly consuming it.
 *
 * <p>This is the recovery path of a reported data-loss defect — a replay that lived only inside the
 * operator call that resumed the instance was lost outright to a crash — so the properties under
 * test are the ones that make it survivable, not the happy path.
 */
class ParkedInputWorkerTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final ProcessInstanceId INSTANCE = new ProcessInstanceId("instance-1");
  private static final ProcessInstanceId OTHER = new ProcessInstanceId("instance-2");
  private static final ProcessType ORDERING = new ProcessType("Ordering");
  private static final ProcessBusinessKey ORDER_1 = new ProcessBusinessKey("order-1");
  private static final ProcessRef REF = new ProcessRef(INSTANCE, ORDERING, ORDER_1);
  private static final ProcessStep AWAITING_PAYMENT = new ProcessStep("awaiting-payment");
  private static final PayloadType PAYLOAD = new PayloadType("sample.payload", 1);

  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final InMemoryProcessTransitionStore transitions =
      new InMemoryProcessTransitionStore(instances);
  private final InMemoryProcessEffectStore effects = new InMemoryProcessEffectStore();
  private final InMemoryProcessDeadlineStore deadlines = new InMemoryProcessDeadlineStore();
  private final RollingBackUnitOfWork unitOfWork =
      new RollingBackUnitOfWork(instances, transitions, effects, deadlines);
  private final ScriptedRuntime runtime = new ScriptedRuntime();

  private record Say(String what) implements ProcessInput {}

  /**
   * Decodes the payload back to the message id that was parked, so a test can name what arrived.
   */
  private static final class SayCodec implements ProcessPayloadCodec<Say> {
    @Override
    public PayloadType payloadType() {
      return PAYLOAD;
    }

    @Override
    public Class<Say> javaType() {
      return Say.class;
    }

    @Override
    public EncodedPayload encode(Say value) {
      return new EncodedPayload(PAYLOAD, value.what().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Say decode(EncodedPayload payload) {
      return new Say(new String(payload.data(), StandardCharsets.UTF_8));
    }
  }

  /**
   * A runtime that records what it was handed and can be told, per replay, to fail or to report the
   * instance as suspended again. The other half of the handshake — that the real runtime answers
   * SUSPENDED rather than parking a replay a second time — is pinned in {@code
   * DefaultProcessRuntimeTest}; this one is about what the worker does with that answer.
   */
  private static final class ScriptedRuntime implements ProcessRuntime {
    private final List<CommandContext> causes = new ArrayList<>();
    private final List<ProcessInput> inputs = new ArrayList<>();
    private final List<String> tenantsSeen = new ArrayList<>();
    private final Map<String, Supplier<RuntimeException>> failing = new HashMap<>();
    private final Map<String, ProcessLifecycle> answers = new HashMap<>();

    @Override
    public ProcessAdvanceResult start(
        ProcessType processType,
        ProcessBusinessKey businessKey,
        ProcessInput input,
        CommandContext cause) {
      throw new UnsupportedOperationException("a replay never starts an instance");
    }

    @Override
    public ProcessAdvanceResult handle(ProcessRef ref, ProcessInput input, CommandContext cause) {
      causes.add(cause);
      inputs.add(input);
      tenantsSeen.add(TenantContext.current().map(tenant -> tenant.value()).orElse("<unbound>"));
      Supplier<RuntimeException> failure = failing.get(cause.messageId());
      if (failure != null) {
        throw failure.get();
      }
      return new ProcessAdvanceResult(
          ref,
          new ProcessRevision(1),
          answers.getOrDefault(cause.messageId(), ProcessLifecycle.RUNNING),
          AWAITING_PAYMENT,
          false,
          "t-" + cause.messageId());
    }

    void failReplayOf(String messageId, RuntimeException failure) {
      failing.put(ParkedInputs.replayIdFor(messageId), () -> failure);
    }

    List<String> replayIds() {
      return causes.stream().map(CommandContext::messageId).toList();
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

  /**
   * Lets a test act in the gap between two of the worker's transactions.
   *
   * <p>The worker scans for owed instances in one transaction and drains each in another, so what
   * the scan saw can be stale by the time the drain runs. That gap is the reason the drain checks
   * the lifecycle a second time, and it cannot be posed at all without somewhere to stand between
   * the two commits.
   */
  private final class BetweenTransactions implements ProcessUnitOfWork {
    private Runnable once;

    @Override
    public <R> R execute(Supplier<R> work) {
      R result = unitOfWork.execute(work);
      if (once != null && !unitOfWork.inExistingTransaction()) {
        Runnable now = once;
        once = null;
        now.run();
      }
      return result;
    }

    @Override
    public boolean inExistingTransaction() {
      return unitOfWork.inExistingTransaction();
    }
  }

  private ParkedInputWorker worker() {
    return worker(10);
  }

  private ParkedInputWorker worker(int batchSize) {
    return worker(batchSize, unitOfWork);
  }

  private ParkedInputWorker worker(int batchSize, ProcessUnitOfWork transactions) {
    return new ParkedInputWorker(
        instances,
        transitions,
        new ProcessPayloadCodecRegistry(List.of(new SayCodec())),
        runtime,
        transactions,
        CLOCK,
        batchSize);
  }

  /** Parks one input on an instance, the way an arrival during a suspension does. */
  private void park(ProcessInstanceId instanceId, String messageId) {
    park(instanceId, messageId, "acme", "corr-1");
  }

  private void park(
      ProcessInstanceId instanceId, String messageId, String tenantId, String correlationId) {
    transitions.append(
        transitions.parked("park-" + messageId, instanceId, messageId, tenantId, correlationId),
        NOW);
  }

  @AfterEach
  void unbindTheTenant() {
    TenantContext.clear();
  }

  @Test
  void aParkedInputIsHandedBackToTheRuntimeAndLeavesTheQueue() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");

    assertEquals(1, worker().pollOnce());

    assertEquals(List.of(new Say("m-1")), runtime.inputs);
    assertEquals(
        List.of(),
        transitions.findUnreplayedParkedInputs(INSTANCE),
        "the debt is settled, so the next poll does not replay it again");
  }

  @Test
  void aReplayRunsUnderTheParkedInputsOwnIdentityAndCausalChain() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1", "acme", "corr-9");

    worker().pollOnce();

    CommandContext cause = runtime.causes.get(0);
    // Not the parked input's own message id: the dedup key would make the replay a duplicate no-op
    // of the park row itself, which is the one thing it must not be.
    assertEquals(ParkedInputs.replayIdFor("m-1"), cause.messageId());
    assertEquals("m-1", cause.causationId(), "the parked input is what caused this replay");
    assertEquals(
        "corr-9", cause.correlationId(), "and the chain it belongs to is the original one");
    assertEquals(Tenants.of("acme"), cause.tenantId());
  }

  @Test
  void aParkedInputWithNoCorrelationStartsOneFromItsOwnReplayIdRatherThanNull() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1", "acme", null);

    worker().pollOnce();

    assertEquals(ParkedInputs.replayIdFor("m-1"), runtime.causes.get(0).correlationId());
  }

  @Test
  void theOwningTenantIsBoundAroundTheReplayEvenThoughTheWorkerThreadHasNone() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1", "acme", "corr-1");

    worker().pollOnce();

    // A worker thread binds nothing, which is why the park row carries the owning tenant: the
    // definition's own tables are scoped by whatever is ambient when it runs.
    assertEquals(List.of("acme"), runtime.tenantsSeen);
  }

  @Test
  void anInstancesParkedInputsAreReplayedInArrivalOrder() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    park(INSTANCE, "m-2");
    park(INSTANCE, "m-3");

    assertEquals(3, worker().pollOnce());

    // Arrival order is transition_seq, not a wall-clock stamp: these three were parked in the same
    // millisecond, and the order is still the order they came in.
    assertEquals(List.of(new Say("m-1"), new Say("m-2"), new Say("m-3")), runtime.inputs);
  }

  @Test
  void aFailedReplayStopsThatInstancesDrainRatherThanLettingTheNextInputOvertakeIt() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    park(INSTANCE, "m-2");
    runtime.failReplayOf("m-1", new IllegalStateException("cannot digest"));

    assertEquals(0, worker().pollOnce());

    // Skipping the failed one and carrying on would apply m-2 to a state m-1 never reached, which
    // is exactly the reordering parking exists to prevent.
    assertEquals(List.of(new Say("m-1")), runtime.inputs);
    assertEquals(2, transitions.findUnreplayedParkedInputs(INSTANCE).size(), "both debts stand");
  }

  @Test
  void aReplayTheDefinitionCannotDigestSuspendsTheInstanceForOperatorRecovery() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    runtime.failReplayOf("m-1", new IllegalStateException("cannot digest"));

    worker().pollOnce();

    // Left running it would be retried at every poll forever. Suspending gives it the same shape as
    // an effect or a deadline that ran out of retries: out of the poll, into the SLI, redrivable.
    assertEquals(ProcessLifecycle.SUSPENDED, instances.row(INSTANCE).lifecycle());
    assertEquals("PARKED_INPUT", instances.suspensionSourceOf(INSTANCE));
    assertEquals(
        Optional.of(ProcessLifecycle.RUNNING),
        instances.row(INSTANCE).resumeLifecycle(),
        "and it remembers what to go back to");
    assertTrue(
        instances.row(INSTANCE).suspensionReason().orElseThrow().contains("cannot digest"),
        "with the failure written down, so an operator is not guessing");
  }

  @Test
  void aPayloadWithNoRegisteredCodecSuspendsRatherThanSpinning() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    transitions.append(
        transitions.parked("park-m-1", INSTANCE, "m-1", "acme", "corr-1", "sample.retired-payload"),
        NOW);

    worker().pollOnce();

    // A payload type whose codec was removed while the input sat in the queue is not a transient
    // fault; it is stuck until somebody acts, and the suspension is what says so.
    assertEquals(ProcessLifecycle.SUSPENDED, instances.row(INSTANCE).lifecycle());
    assertEquals(1, transitions.findUnreplayedParkedInputs(INSTANCE).size());
  }

  @Test
  void aReplayThatFindsTheInstanceSuspendedAgainLeavesTheDebtStanding() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    park(INSTANCE, "m-2");
    runtime.answers.put(ParkedInputs.replayIdFor("m-1"), ProcessLifecycle.SUSPENDED);

    assertEquals(0, worker().pollOnce());

    // SUSPENDED means the runtime declined to apply the input at all, so consuming the debt here
    // would drop it silently — and m-2 must not go ahead of it either.
    assertEquals(2, transitions.findUnreplayedParkedInputs(INSTANCE).size());
    assertEquals(List.of(new Say("m-1")), runtime.inputs);
  }

  @Test
  void anInstanceTheScanOfferedIsNotOfferedAtAllOnceItIsSuspended() {
    instances.given(INSTANCE, ProcessLifecycle.SUSPENDED);
    park(INSTANCE, "m-1");

    assertEquals(0, worker().pollOnce());

    // Replaying into a suspension would only park the input again, so the worklist query excludes
    // it outright — the debt keeps standing until an operator resumes the instance.
    assertEquals(List.of(), runtime.inputs);
    assertEquals(1, transitions.findUnreplayedParkedInputs(INSTANCE).size());
  }

  @Test
  void anInstanceEndedInTheGapBetweenTheScanAndTheDrainIsLeftAlone() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    BetweenTransactions gap = new BetweenTransactions();
    gap.once = () -> instances.put(instances.asLifecycle(INSTANCE, ProcessLifecycle.CANCELLED));

    assertEquals(0, worker(10, gap).pollOnce());

    // An ended instance can no longer be advanced at all, so the input stays owed rather than
    // being handed to a runtime that would only refuse it.
    assertEquals(List.of(), runtime.inputs);
    assertEquals(1, transitions.findUnreplayedParkedInputs(INSTANCE).size());
  }

  @Test
  void aCompensatingInstanceIsDrainedTooBecauseItIsStillAdvancing() {
    instances.given(INSTANCE, ProcessLifecycle.COMPENSATING);
    park(INSTANCE, "m-1");

    assertEquals(1, worker().pollOnce());
  }

  @Test
  void oneInstancesFailureDoesNotStopAnother() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    instances.given(OTHER, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    park(OTHER, "m-2");
    runtime.failReplayOf("m-1", new IllegalStateException("cannot digest"));

    assertEquals(1, worker().pollOnce());

    // Ordering is a per-instance promise, so a stuck instance must not hold the queue for the rest.
    assertEquals(0, transitions.findUnreplayedParkedInputs(OTHER).size());
    assertEquals(ProcessLifecycle.RUNNING, instances.row(OTHER).lifecycle());
  }

  @Test
  void aPollTakesAtMostItsBatchOfInstances() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    instances.given(OTHER, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    park(OTHER, "m-2");

    assertEquals(1, worker(1).pollOnce());

    assertEquals(1, runtime.inputs.size());
  }

  @Test
  void aDebtIsSettledOnlyAfterTheReplayItselfCommitted() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    runtime.failReplayOf("m-1", new IllegalStateException("crash"));

    worker().pollOnce();

    // The marker is written after the advance's own transaction, never inside it: a crash in
    // between leaves the input in the queue and the replay happens again, which the runtime
    // answers as a duplicate no-op. Marking first would lose the input outright.
    assertNull(transitions.row("park-m-1").replayedAt());
  }

  @Test
  void anInstanceThatDisappearedBetweenTheScanAndTheDrainIsLeftAlone() {
    park(INSTANCE, "m-1");

    assertEquals(0, worker().pollOnce());

    assertEquals(List.of(), runtime.inputs);
  }

  @Test
  void anInstanceCancelledWhileTheFailingReplayWasInFlightIsNotSuspended() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    // An operator cancels it from under the worker, at the moment the replay fails.
    runtime.failing.put(
        ParkedInputs.replayIdFor("m-1"),
        () -> {
          instances.put(instances.asLifecycle(INSTANCE, ProcessLifecycle.CANCELLED));
          return new IllegalStateException("cannot digest");
        });

    worker().pollOnce();

    // Rewriting a terminal lifecycle to SUSPENDED would lose the outcome it already reached, which
    // is why the suspension re-reads the row under lock and asks whether it may still suspend.
    assertEquals(ProcessLifecycle.CANCELLED, instances.row(INSTANCE).lifecycle());
    assertNull(instances.suspensionSourceOf(INSTANCE));
  }

  @Test
  void aSuspensionReasonIsTruncatedRatherThanOverflowingItsColumn() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    park(INSTANCE, "m-1");
    runtime.failReplayOf("m-1", new IllegalStateException("x".repeat(2_000)));

    worker().pollOnce();

    assertEquals(512, instances.row(INSTANCE).suspensionReason().orElseThrow().length());
  }

  @Test
  void aQuietQueueCostsOneScanAndNothingElse() {
    assertEquals(0, worker().pollOnce());

    assertEquals(List.of(), runtime.replayIds());
  }
}
