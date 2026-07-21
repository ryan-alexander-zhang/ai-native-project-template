package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.exception.ProcessAlreadyExistsException;
import com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessQuery;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessRuntime;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Atomic-advance contract of JdbcProcessRuntime against an in-memory H2. */
class JdbcProcessRuntimeTest {

  private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");

  private JdbcTemplate jdbc;
  private JdbcProcessRuntime runtime;
  private JdbcProcessQuery query;
  private final AtomicInteger ids = new AtomicInteger();

  private JdbcProcessRuntime build(DuplicateBusinessKeyPolicy policy) {
    var instances = new JdbcProcessInstanceStore(jdbc);
    var transitions = new JdbcProcessTransitionStore(jdbc);
    var effects = new JdbcProcessEffectStore(jdbc);
    var deadlines = new JdbcProcessDeadlineStore(jdbc);
    Clock clock = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);
    query = new JdbcProcessQuery(instances, transitions, effects, deadlines, clock);
    return new JdbcProcessRuntime(
        instances,
        transitions,
        effects,
        deadlines,
        new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
        new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
        new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
        new JdbcProcessUnitOfWork(new DataSourceTransactionManager(dataSource)),
        clock,
        () -> "id-" + ids.incrementAndGet(),
        policy,
        3);
  }

  private DataSource dataSource;

  @BeforeEach
  void setUp() {
    dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V1__aipersimmon_process_manager.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V2__drop_trace_id.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    runtime = build(DuplicateBusinessKeyPolicy.REJECT);
  }

  private ProcessAdvanceResult start(String messageId) {
    return runtime.start(
        TestFulfilment.TYPE,
        ORDER,
        new TestFulfilment.Started("order-1"),
        CommandContext.root(messageId));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  @Test
  void startPersistsInstanceTransitionAndEffectAtomicallyWithDurableIdentity() {
    ProcessAdvanceResult result = start("msg-1");

    assertFalse(result.duplicate());
    assertEquals(ProcessLifecycle.RUNNING, result.lifecycle());
    assertEquals(1L, count("aipersimmon_process_instance"));
    assertEquals(1L, count("aipersimmon_process_transition"));
    assertEquals(1L, count("aipersimmon_process_effect"));

    Map<String, Object> effect = jdbc.queryForMap("SELECT * FROM aipersimmon_process_effect");
    String expectedEffectId = result.transitionId() + "#0";
    assertEquals(expectedEffectId, effect.get("EFFECT_ID"));
    assertEquals(expectedEffectId, effect.get("MESSAGE_ID"), "messageId must equal effectId");
    assertEquals("msg-1", effect.get("CORRELATION_ID"), "correlation inherited from the cause");
    assertEquals("msg-1", effect.get("CAUSATION_ID"), "causation is the input message id");
    assertEquals("DISPATCH_COMMAND", effect.get("EFFECT_KIND"));
    assertEquals("PENDING", effect.get("STATUS"));
  }

  @Test
  void duplicateStartWithSameMessageIdIsANoOp() {
    ProcessAdvanceResult first = start("msg-1");
    ProcessAdvanceResult second = start("msg-1");

    assertFalse(first.duplicate());
    assertTrue(second.duplicate());
    assertEquals(first.transitionId(), second.transitionId());
    assertEquals(1L, count("aipersimmon_process_transition"));
    assertEquals(1L, count("aipersimmon_process_effect"));
  }

  @Test
  void differentMessageIdSameBusinessKeyRejectsByDefault() {
    start("msg-1");
    assertThrows(ProcessAlreadyExistsException.class, () -> start("msg-2"));
    assertEquals(1L, count("aipersimmon_process_transition"));
  }

  @Test
  void differentMessageIdSameBusinessKeyFoldsUnderFoldPolicy() {
    runtime = build(DuplicateBusinessKeyPolicy.FOLD);
    start("msg-1");
    ProcessAdvanceResult folded = start("msg-2");

    assertTrue(folded.duplicate());
    assertEquals(1L, count("aipersimmon_process_transition"));
    assertEquals(1L, count("aipersimmon_process_effect"));
  }

  @Test
  void handleAdvancesAndIncrementsRevision() {
    ProcessAdvanceResult started = start("msg-1");
    ProcessAdvanceResult advanced =
        runtime.handle(
            started.processRef(), new TestFulfilment.Advance(), CommandContext.root("msg-2"));

    assertFalse(advanced.duplicate());
    assertEquals(2L, advanced.revision().value());
    assertEquals(ProcessLifecycle.RUNNING, advanced.lifecycle());
    assertEquals("S2", advanced.step().value());
    assertEquals(2L, count("aipersimmon_process_transition"));
    assertEquals(2L, count("aipersimmon_process_effect"));
  }

  @Test
  void handleOnUnknownInstanceThrowsNotFound() {
    ProcessRef ghost = new ProcessRef(new ProcessInstanceId("nope"), TestFulfilment.TYPE, ORDER);
    assertThrows(
        ProcessNotFoundException.class,
        () -> runtime.handle(ghost, new TestFulfilment.Advance(), CommandContext.root("m")));
  }

  @Test
  void handleWithARealInstanceIdButWrongProcessTypeIsRejected() {
    ProcessAdvanceResult started = start("msg-1");
    // A ref carrying the real instanceId but a different processType must fail fast at the load
    // boundary rather than driving the real instance under the wrong definition/codec.
    ProcessRef mismatched =
        new ProcessRef(started.processRef().instanceId(), new ProcessType("test.other"), ORDER);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.handle(mismatched, new TestFulfilment.Advance(), CommandContext.root("msg-2")));
    // The real instance is untouched: still one transition, revision 1.
    assertEquals(1L, count("aipersimmon_process_transition"));
    assertEquals(1L, query.find(started.processRef()).orElseThrow().revision().value());
  }

  @Test
  void aFailingDecisionRollsBackTheWholeAdvance() {
    ProcessAdvanceResult started = start("msg-1");
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.handle(
                started.processRef(), new TestFulfilment.Boom(), CommandContext.root("msg-2")));

    // Nothing from the failed advance persisted: still one transition, one effect, revision 1.
    assertEquals(1L, count("aipersimmon_process_transition"));
    assertEquals(1L, count("aipersimmon_process_effect"));
    ProcessView view = query.find(started.processRef()).orElseThrow();
    assertEquals(1L, view.revision().value());
    assertEquals("S1", view.step().value());
  }

  @Test
  void anIllegalLifecycleTransitionIsRejected() {
    ProcessAdvanceResult started = start("msg-1");
    runtime.handle(
        started.processRef(), new TestFulfilment.EnterCompensating(), CommandContext.root("msg-2"));
    // COMPENSATING -> RUNNING is illegal.
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.handle(
                started.processRef(),
                new TestFulfilment.IllegalBack(),
                CommandContext.root("msg-3")));
  }

  @Test
  void ordinaryInputToATerminalInstanceIsAnIdempotentNoOp() {
    ProcessAdvanceResult started = start("msg-1");
    runtime.handle(started.processRef(), new TestFulfilment.Finish(), CommandContext.root("msg-2"));

    ProcessAdvanceResult afterDone =
        runtime.handle(
            started.processRef(), new TestFulfilment.Advance(), CommandContext.root("msg-3"));

    assertTrue(afterDone.duplicate());
    assertEquals(ProcessLifecycle.COMPLETED, afterDone.lifecycle());
    assertEquals(
        2L, count("aipersimmon_process_transition"), "no new transition for a terminal no-op");
  }

  @Test
  void schedulingADeadlinePersistsAPendingDeadlineRow() {
    ProcessAdvanceResult started = start("msg-1");
    runtime.handle(
        started.processRef(), new TestFulfilment.ArmDeadline(), CommandContext.root("msg-2"));

    Map<String, Object> deadline = jdbc.queryForMap("SELECT * FROM aipersimmon_process_deadline");
    assertEquals("REVIEW", deadline.get("NAME"));
    assertEquals(1L, ((Number) deadline.get("GENERATION")).longValue());
    assertEquals("PENDING", deadline.get("STATUS"));
    // Arming a deadline stages no command effect (still just the start's effect).
    assertEquals(1L, count("aipersimmon_process_effect"));
  }

  @Test
  void queryReturnsTheCurrentView() {
    ProcessAdvanceResult started = start("msg-1");
    Optional<ProcessView> view = query.find(started.processRef());

    assertTrue(view.isPresent());
    assertEquals(ProcessLifecycle.RUNNING, view.get().lifecycle());
    assertEquals("S1", view.get().step().value());
    assertEquals(1L, view.get().revision().value());
    assertEquals(TestFulfilment.VERSION, view.get().definitionVersion());
  }
}
