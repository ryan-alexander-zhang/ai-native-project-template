package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.engine.deadline.ProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.engine.operation.ProcessOperations;
import com.aipersimmon.ddd.processmanager.engine.relay.CommandEffectDispatcher;
import com.aipersimmon.ddd.processmanager.engine.relay.EffectDispatcherRegistry;
import com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.engine.replay.ParkedInputWorker;
import com.aipersimmon.ddd.processmanager.engine.retry.ProcessRetryPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Operator recovery + suspended-input parking against H2. */
class JdbcProcessOperationsTest {

  private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private JdbcTemplate jdbc;
  private DefaultProcessRuntime runtime;
  private JdbcProcessEffectStore effectStore;
  private JdbcProcessInstanceStore instanceStore;
  private JdbcProcessTransitionStore transitionStore;
  private JdbcProcessDeadlineStore deadlineStore;
  private SpringTxProcessUnitOfWork unitOfWork;
  private ProcessOperations operations;
  private ParkedInputWorker parkedInputWorker;
  private final AtomicUpdateProcessDialect dialect = new AtomicUpdateProcessDialect("h2");
  private final FailingBus bus = new FailingBus();
  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger tokens = new AtomicInteger();

  @BeforeEach
  void setUp() {
    DataSource dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V3__add_tenant_id.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V4__parked_input_replay_marker.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    instanceStore = new JdbcProcessInstanceStore(jdbc);
    transitionStore = new JdbcProcessTransitionStore(jdbc);
    effectStore = new JdbcProcessEffectStore(jdbc);
    deadlineStore = new JdbcProcessDeadlineStore(jdbc);
    unitOfWork = new SpringTxProcessUnitOfWork(new DataSourceTransactionManager(dataSource));
    ProcessPayloadCodecRegistry payloadCodecs =
        new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs());
    runtime =
        new DefaultProcessRuntime(
            instanceStore,
            transitionStore,
            effectStore,
            deadlineStore,
            new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
            payloadCodecs,
            new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
            unitOfWork,
            CLOCK,
            () -> "id-" + ids.incrementAndGet(),
            DuplicateBusinessKeyPolicy.REJECT,
            3);
    operations =
        new ProcessOperations(
            instanceStore,
            transitionStore,
            effectStore,
            deadlineStore,
            unitOfWork,
            CLOCK,
            () -> "op-" + ids.incrementAndGet());
    parkedInputWorker =
        new ParkedInputWorker(
            instanceStore, transitionStore, payloadCodecs, runtime, unitOfWork, CLOCK, 10);
  }

  private ProcessAdvanceResult start() {
    return runtime.start(
        TestFulfilment.TYPE,
        ORDER,
        new TestFulfilment.Started("order-1"),
        CommandContext.root(Tenants.ROOT, "msg-start"));
  }

  private String lifecycle() {
    return jdbc.queryForObject("SELECT lifecycle FROM aipersimmon_process_instance", String.class);
  }

  private String step() {
    return jdbc.queryForObject(
        "SELECT business_step FROM aipersimmon_process_instance", String.class);
  }

  /** The decoded state payload ({@code step|count}); the column holds it base64-encoded. */
  private String state() {
    String encoded =
        jdbc.queryForObject("SELECT state_payload FROM aipersimmon_process_instance", String.class);
    return new String(
        java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
  }

  private long owedParkedInputs() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_process_transition "
            + "WHERE transition_kind = 'PARKED' AND replayed_at IS NULL",
        Long.class);
  }

  private String suspendViaDeadDeadline() {
    ProcessAdvanceResult started = start();
    runtime.handle(
        started.processRef(),
        new TestFulfilment.ArmPoisonDeadline(),
        CommandContext.root(Tenants.ROOT, "msg-arm"));
    ProcessDeadlineWorker worker =
        new ProcessDeadlineWorker(
            new JdbcProcessClaimStrategy(jdbc, dialect, new WorkerId("dw")),
            deadlineStore,
            instanceStore,
            new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
            runtime,
            unitOfWork,
            zeroBackoff(1),
            CLOCK,
            10,
            Duration.ofSeconds(30),
            () -> "dlease-" + tokens.incrementAndGet());
    worker.pollOnce(); // one failed fire with maxAttempts=1 -> DEAD + SUSPENDED (source DEADLINE)
    return jdbc.queryForObject(
        "SELECT deadline_id FROM aipersimmon_process_deadline", String.class);
  }

  private void suspendViaDeadEffect() {
    ProcessEffectRelay relay =
        new ProcessEffectRelay(
            new JdbcProcessClaimStrategy(jdbc, dialect, new WorkerId("w")),
            effectStore,
            instanceStore,
            new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
            new EffectDispatcherRegistry(List.of(new CommandEffectDispatcher(bus))),
            unitOfWork,
            zeroBackoff(1),
            CLOCK,
            10,
            Duration.ofSeconds(30),
            () -> "lease-" + tokens.incrementAndGet());
    relay.pollOnce(); // one failed attempt with maxAttempts=1 -> DEAD + SUSPENDED
  }

  @Test
  void aSuspendedInstanceParksInputInsteadOfReboundingToTheMessageLayer() {
    ProcessAdvanceResult started = start();
    suspendViaDeadEffect();
    assertEquals("SUSPENDED", lifecycle());

    // handle returns normally (parked), so the transport can ack instead of retrying forever.
    ProcessAdvanceResult parked =
        runtime.handle(
            started.processRef(),
            new TestFulfilment.Advance(),
            CommandContext.root(Tenants.ROOT, "msg-adv"));
    assertFalse(parked.duplicate());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'PARKED'",
            Long.class));

    // A redelivery of the same input while suspended is a duplicate no-op — it is not parked twice.
    ProcessAdvanceResult again =
        runtime.handle(
            started.processRef(),
            new TestFulfilment.Advance(),
            CommandContext.root(Tenants.ROOT, "msg-adv"));
    assertTrue(again.duplicate());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'PARKED'",
            Long.class));
  }

  @Test
  void redriveResumesAndLeavesTheReplayOwedUntilTheWorkerDrainsIt() {
    ProcessAdvanceResult started = start();
    String deadEffectId = started.transitionId() + "#0";
    suspendViaDeadEffect();
    runtime.handle(
        started.processRef(),
        new TestFulfilment.Advance(),
        CommandContext.root(Tenants.ROOT, "msg-adv"));

    operations.redriveEffect(deadEffectId, "operator-1", "transient outage cleared");

    assertEquals("RUNNING", lifecycle(), "resumed to its recorded resume lifecycle");
    // The operator's call committed the resume and nothing else. The replay is owed, durably, so a
    // crash here loses nothing — which is exactly why it is not done inside redrive.
    assertEquals("S1", step(), "the parked input has not been replayed by redrive itself");
    assertEquals(1, owedParkedInputs(), "the replay is recorded as still owed");

    assertEquals(1, parkedInputWorker.pollOnce(), "the worker drains the queue");

    assertEquals("S2", step(), "the parked Advance was replayed");
    assertEquals(0, owedParkedInputs(), "and the debt is settled");
    assertEquals(
        "PENDING",
        jdbc.queryForObject(
            "SELECT status FROM aipersimmon_process_effect WHERE effect_id = ?",
            String.class,
            deadEffectId),
        "the redriven effect is back to PENDING for the relay");
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'OPERATOR_REDRIVE_EFFECT'",
            Long.class));
  }

  @Test
  void redrivingADeadDeadlineResumesTheInstance() {
    String deadlineId = suspendViaDeadDeadline();
    assertEquals("SUSPENDED", lifecycle());

    operations.redriveDeadline(deadlineId, 1L, "operator-1", "poison fixed");

    assertEquals("RUNNING", lifecycle(), "resumed once no dead work remains");
    assertEquals(
        "PENDING",
        jdbc.queryForObject(
            "SELECT status FROM aipersimmon_process_deadline WHERE deadline_id = ?",
            String.class,
            deadlineId),
        "the redriven deadline is back to PENDING for the worker");
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'OPERATOR_REDRIVE_DEADLINE'",
            Long.class));
  }

  @Test
  void redriveDeadlineRejectsAStaleGeneration() {
    String deadlineId = suspendViaDeadDeadline();

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> operations.redriveDeadline(deadlineId, 99L, "operator-1", "wrong generation"));

    assertEquals(
        "DEAD",
        jdbc.queryForObject(
            "SELECT status FROM aipersimmon_process_deadline WHERE deadline_id = ?",
            String.class,
            deadlineId),
        "a mismatched generation leaves the deadline untouched");
  }

  @Test
  void multipleParkedInputsAreReplayedInArrivalOrderOnResume() {
    ProcessAdvanceResult started = start();
    String deadEffectId = started.transitionId() + "#0";
    suspendViaDeadEffect();

    // Two distinct inputs arrive while suspended; both are parked, not rebounded.
    runtime.handle(
        started.processRef(),
        new TestFulfilment.Advance(),
        CommandContext.root(Tenants.ROOT, "msg-adv"));
    runtime.handle(
        started.processRef(),
        new TestFulfilment.FanOut(),
        CommandContext.root(Tenants.ROOT, "msg-fan"));
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'PARKED'",
            Long.class));

    operations.redriveEffect(deadEffectId, "operator-1", "outage cleared");
    assertEquals(2, parkedInputWorker.pollOnce(), "both owed inputs drain in one poll");

    assertEquals("RUNNING", lifecycle());
    // Arrival order: Advance (S1->S2) then FanOut (S2->FAN); the reverse would leave step at S2.
    assertEquals("FAN", step(), "parked inputs replayed in arrival order");
  }

  @Test
  void aReplayThatAlreadyCommittedIsNotAppliedTwice() {
    ProcessAdvanceResult started = start();
    String deadEffectId = started.transitionId() + "#0";
    suspendViaDeadEffect();
    runtime.handle(
        started.processRef(),
        new TestFulfilment.Advance(),
        CommandContext.root(Tenants.ROOT, "msg-adv"));
    operations.redriveEffect(deadEffectId, "operator-1", "outage cleared");
    parkedInputWorker.pollOnce();
    assertEquals("S2|1", state(), "Advance ran once, incrementing the counter once");

    // Simulate a crash between the replay's commit and its marker: the debt reappears, and the next
    // poll — on this node or another — replays an input whose advance is already recorded. The
    // process-level dedup makes that a no-op, so the state must not move again.
    jdbc.update(
        "UPDATE aipersimmon_process_transition SET replayed_at = NULL "
            + "WHERE transition_kind = 'PARKED'");
    parkedInputWorker.pollOnce();

    assertEquals("S2|1", state(), "the duplicate replay did not advance the process a second time");
    assertEquals(0, owedParkedInputs(), "and the marker is settled after the retry");
  }

  @Test
  void aParkedInputWhoseReplayFailsSuspendsTheInstanceInsteadOfRetryingForever() {
    ProcessAdvanceResult started = start();
    String deadEffectId = started.transitionId() + "#0";
    suspendViaDeadEffect();
    // Boom's decision throws, so its replay cannot succeed however often it is attempted.
    runtime.handle(
        started.processRef(),
        new TestFulfilment.Boom(),
        CommandContext.root(Tenants.ROOT, "msg-boom"));
    operations.redriveEffect(deadEffectId, "operator-1", "outage cleared");

    assertEquals(0, parkedInputWorker.pollOnce(), "nothing was replayed");

    assertEquals("SUSPENDED", lifecycle(), "the instance is parked for operator recovery");
    assertEquals(
        "PARKED_INPUT",
        jdbc.queryForObject(
            "SELECT suspension_source FROM aipersimmon_process_instance", String.class),
        "the suspension names the parked input as its source");
    assertEquals(1, owedParkedInputs(), "the input is still owed, not silently dropped");
    assertEquals(
        0, parkedInputWorker.pollOnce(), "a suspended instance is no longer offered to the worker");
  }

  @Test
  void aParkedInputIsNotReplayedWhileTheInstanceIsStillSuspended() {
    ProcessAdvanceResult started = start();
    suspendViaDeadEffect();
    runtime.handle(
        started.processRef(),
        new TestFulfilment.Advance(),
        CommandContext.root(Tenants.ROOT, "msg-adv"));

    assertEquals(0, parkedInputWorker.pollOnce(), "a suspended instance would only re-park it");
    assertEquals("S1", step());
    assertEquals(1, owedParkedInputs());
  }

  @Test
  void aReplayedParkedInputKeepsItsOriginalCorrelation() {
    ProcessAdvanceResult started = start();
    String deadEffectId = started.transitionId() + "#0";
    suspendViaDeadEffect();
    // Park an input under a specific correlation while suspended.
    runtime.handle(
        started.processRef(),
        new TestFulfilment.Advance(),
        CommandContext.root(Tenants.ROOT, "msg-adv"));

    operations.redriveEffect(deadEffectId, "operator-1", "outage cleared");
    parkedInputWorker.pollOnce();

    // The replay runs under a synthetic message id but must keep the parked input's causal chain.
    var replayed =
        jdbc.queryForMap(
            "SELECT correlation_id FROM aipersimmon_process_transition "
                + "WHERE input_message_id = 'parked:msg-adv'");
    assertEquals(
        "msg-adv",
        replayed.get("CORRELATION_ID"),
        "replay stays on the parked input's correlation");
  }

  @Test
  void cancelProcessTerminatesAndCancelsPendingWork() {
    ProcessAdvanceResult started = start();
    // Arm a timer so the cancel has a live deadline to retire, not just an effect.
    ProcessAdvanceResult armed =
        runtime.handle(
            started.processRef(),
            new TestFulfilment.ArmDeadline(),
            CommandContext.root(Tenants.ROOT, "msg-arm"));

    operations.cancelProcess(
        started.processRef(), armed.revision().value(), "operator-1", "customer request");

    assertEquals("CANCELLED", lifecycle());
    assertEquals(
        "CANCELLED",
        jdbc.queryForObject("SELECT status FROM aipersimmon_process_deadline", String.class),
        "a cancelled coordinator leaves no live timer behind");
    assertEquals(
        "PROCESS_CANCELLED",
        jdbc.queryForObject("SELECT outcome FROM aipersimmon_process_instance", String.class));
    assertEquals(
        "CANCELLED",
        jdbc.queryForObject("SELECT status FROM aipersimmon_process_effect", String.class),
        "the not-yet-dispatched effect is cancelled");
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'OPERATOR_CANCEL'",
            Long.class));
  }

  @Test
  void cancelProcessWithARealInstanceIdButWrongProcessTypeIsRejected() {
    ProcessAdvanceResult started = start();
    // A ref with the real instanceId but a wrong processType must not cancel the real instance.
    ProcessRef mismatched =
        new ProcessRef(started.processRef().instanceId(), new ProcessType("test.other"), ORDER);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            operations.cancelProcess(
                mismatched, started.revision().value(), "operator-1", "wrong type"));

    assertEquals("RUNNING", lifecycle(), "the real instance is not cancelled");
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_transition WHERE transition_kind = 'OPERATOR_CANCEL'",
            Long.class));
  }

  private static ProcessRetryPolicy zeroBackoff(int maxAttempts) {
    return new ProcessRetryPolicy() {
      @Override
      public Duration backoff(int attempt) {
        return Duration.ZERO;
      }

      @Override
      public int maxAttempts() {
        return maxAttempts;
      }
    };
  }

  /** A CommandBus whose sendAs always fails, to drive an effect to DEAD. */
  static final class FailingBus implements CommandBus {
    @Override
    public <R> R send(Command<R> command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <R> R send(Command<R> command, CommandContext cause) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <R> R sendAs(Command<R> command, CommandContext messageContext) {
      throw new IllegalStateException("downstream unavailable");
    }
  }
}
