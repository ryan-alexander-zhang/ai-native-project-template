package com.aipersimmon.ddd.processmanager.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessRuntime;
import com.aipersimmon.ddd.processmanager.engine.runtime.DuplicateBusinessKeyPolicy;
import com.aipersimmon.ddd.processmanager.engine.runtime.SpringTxProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceCriteria;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.VersionRef;
import com.aipersimmon.ddd.processmanager.exception.ProcessAlreadyExistsException;
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.tenancy.Tenants;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durable contract of one advance beyond its happy path: an ended instance leaves no live
 * timer, a conflict is retried only when the advance owns its transaction, and a lost race on the
 * business key resolves under the duplicate policy rather than leaking the store's exception.
 */
class MybatisProcessAdvanceContractTest {

  private static final ProcessBusinessKey ORDER = new ProcessBusinessKey("order-1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC);

  private DataSource dataSource;
  private JdbcTemplate jdbc;
  private ProcessStores stores;
  private MybatisProcessInstanceStore instances;

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
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V3__add_tenant_id.sql")
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V4__parked_input_replay_marker.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    stores = ProcessStores.over(dataSource);
    instances = stores.instances();
  }

  private DefaultProcessRuntime runtime(
      ProcessInstanceStore instanceStore, DuplicateBusinessKeyPolicy policy, int maxRetries) {
    AtomicInteger ids = new AtomicInteger();
    return new DefaultProcessRuntime(
        instanceStore,
        stores.transitions(),
        stores.effects(),
        stores.deadlines(),
        new ProcessDefinitionRegistry(List.of(new TestFulfilment.Definition())),
        new ProcessPayloadCodecRegistry(TestFulfilment.payloadCodecs()),
        new ProcessStateCodecRegistry(List.of(TestFulfilment.stateCodec())),
        new SpringTxProcessUnitOfWork(new DataSourceTransactionManager(dataSource)),
        CLOCK,
        () -> "id-" + ids.incrementAndGet(),
        policy,
        maxRetries);
  }

  private ProcessAdvanceResult start(DefaultProcessRuntime runtime, String messageId) {
    return runtime.start(
        TestFulfilment.TYPE,
        ORDER,
        new TestFulfilment.Started("order-1"),
        CommandContext.root(Tenants.ROOT, messageId));
  }

  @Test
  void anEndedInstanceLeavesNoLiveDeadlineBehind() {
    DefaultProcessRuntime runtime = runtime(instances, DuplicateBusinessKeyPolicy.REJECT, 3);
    ProcessAdvanceResult started = start(runtime, "msg-start");
    runtime.handle(
        started.processRef(),
        new TestFulfilment.ArmDeadline(),
        CommandContext.root(Tenants.ROOT, "msg-arm"));
    assertEquals(
        "PENDING",
        jdbc.queryForObject("SELECT status FROM aipersimmon_process_deadline", String.class));

    runtime.handle(
        started.processRef(),
        new TestFulfilment.Finish(),
        CommandContext.root(Tenants.ROOT, "msg-finish"));

    // Left PENDING, this row could never be claimed again, since the claim query only offers timers
    // of active instances — yet it would still count as due work in the backlog SLI, so the health
    // indicator would report DEGRADED forever with a monotonically growing age.
    assertEquals(
        "CANCELLED",
        jdbc.queryForObject("SELECT status FROM aipersimmon_process_deadline", String.class),
        "the timer of a completed instance is retired in the terminal advance");
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE completed_at IS NOT NULL",
            Long.class),
        "and it is stamped, so the audit shows when it was retired");
  }

  @Test
  void aTerminalDecisionThatSchedulesADeadlineIsRejected() {
    DefaultProcessRuntime runtime = runtime(instances, DuplicateBusinessKeyPolicy.REJECT, 3);
    ProcessAdvanceResult started = start(runtime, "msg-start");

    IllegalStateException rejected =
        assertThrows(
            IllegalStateException.class,
            () ->
                runtime.handle(
                    started.processRef(),
                    new TestFulfilment.FinishAndArmDeadline(),
                    CommandContext.root(Tenants.ROOT, "msg-finish")));

    assertTrue(
        rejected.getMessage().contains("REVIEW"),
        "the message names the unreachable timer: " + rejected.getMessage());
    assertEquals(
        "RUNNING",
        jdbc.queryForObject("SELECT lifecycle FROM aipersimmon_process_instance", String.class),
        "the whole advance rolled back, so the definition bug cannot half-apply");
  }

  @Test
  void aRevisionConflictIsRetriedWhenTheAdvanceOwnsItsTransaction() {
    ConflictOnceInstanceStore conflicting = new ConflictOnceInstanceStore(instances);
    DefaultProcessRuntime runtime = runtime(conflicting, DuplicateBusinessKeyPolicy.REJECT, 3);
    ProcessAdvanceResult started = start(runtime, "msg-start");

    ProcessAdvanceResult advanced =
        runtime.handle(
            started.processRef(),
            new TestFulfilment.Advance(),
            CommandContext.root(Tenants.ROOT, "msg-adv"));

    assertEquals(ProcessLifecycle.RUNNING, advanced.lifecycle());
    assertEquals(
        2, conflicting.snapshotUpdates, "the first attempt conflicted and the second succeeded");
    assertEquals(
        "S2",
        jdbc.queryForObject(
            "SELECT business_step FROM aipersimmon_process_instance", String.class));
  }

  @Test
  void aRevisionConflictInsideACallersTransactionPropagatesInsteadOfBeingRetried() {
    ConflictOnceInstanceStore conflicting = new ConflictOnceInstanceStore(instances);
    DefaultProcessRuntime runtime = runtime(conflicting, DuplicateBusinessKeyPolicy.REJECT, 3);
    ProcessAdvanceResult started = start(runtime, "msg-start");
    conflicting.snapshotUpdates = 0;

    // The advance joins the caller's transaction — the composition this runtime advertises for a
    // command handler or an Inbox listener. The first attempt's rollback has already doomed that
    // shared transaction, so retrying inside it could only fail while replacing the real cause.
    TransactionTemplate caller =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    assertThrows(
        StaleProcessRevisionException.class,
        () ->
            caller.executeWithoutResult(
                status ->
                    runtime.handle(
                        started.processRef(),
                        new TestFulfilment.Advance(),
                        CommandContext.root(Tenants.ROOT, "msg-adv"))));

    assertEquals(
        1,
        conflicting.snapshotUpdates,
        "exactly one attempt: the retry is the caller's rollback and the transport's redelivery");
  }

  @Test
  void losingTheRaceOnTheBusinessKeyResolvesUnderTheDuplicatePolicy() {
    // The instance exists but this advance's lookup cannot see it, so the insert hits
    // UNIQUE(tenant_id, process_type, business_key) — the shape of two concurrent first starts. The
    // store must surface that as a retriable conflict, so the retry re-reads and the configured
    // policy decides, rather than a DuplicateKeyException escaping past every policy.
    DefaultProcessRuntime seed = runtime(instances, DuplicateBusinessKeyPolicy.REJECT, 3);
    start(seed, "msg-first");

    BlindLookupInstanceStore blind = new BlindLookupInstanceStore(instances);
    DefaultProcessRuntime rejecting = runtime(blind, DuplicateBusinessKeyPolicy.REJECT, 3);
    assertThrows(ProcessAlreadyExistsException.class, () -> start(rejecting, "msg-second"));

    BlindLookupInstanceStore blindAgain = new BlindLookupInstanceStore(instances);
    DefaultProcessRuntime folding = runtime(blindAgain, DuplicateBusinessKeyPolicy.FOLD, 3);
    ProcessAdvanceResult folded = start(folding, "msg-third");
    assertTrue(folded.duplicate(), "under fold, the loser folds into the winning instance");
    assertEquals(
        1L, jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_process_instance", Long.class));
  }

  /** Delegating store whose first {@code updateSnapshot} reports a revision conflict. */
  private static final class ConflictOnceInstanceStore extends DelegatingInstanceStore {
    private int snapshotUpdates;

    ConflictOnceInstanceStore(ProcessInstanceStore delegate) {
      super(delegate);
    }

    @Override
    public int updateSnapshot(ProcessInstanceRow row, ProcessRevision expected, Instant now) {
      snapshotUpdates++;
      if (snapshotUpdates == 1) {
        return 0; // as if a concurrent transition moved the revision on
      }
      return super.updateSnapshot(row, expected, now);
    }
  }

  /** Delegating store that cannot see an existing instance by business key, only on first look. */
  private static final class BlindLookupInstanceStore extends DelegatingInstanceStore {
    private int lookups;

    BlindLookupInstanceStore(ProcessInstanceStore delegate) {
      super(delegate);
    }

    @Override
    public Optional<ProcessInstanceRow> findByBusinessKey(
        String tenantId, ProcessType processType, ProcessBusinessKey businessKey) {
      lookups++;
      if (lookups == 1) {
        return Optional.empty();
      }
      return super.findByBusinessKey(tenantId, processType, businessKey);
    }
  }

  /** Boilerplate pass-through so each fake overrides only the one method it is about. */
  private abstract static class DelegatingInstanceStore implements ProcessInstanceStore {
    private final ProcessInstanceStore delegate;

    DelegatingInstanceStore(ProcessInstanceStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<ProcessInstanceRow> find(ProcessInstanceId instanceId) {
      return delegate.find(instanceId);
    }

    @Override
    public Optional<ProcessInstanceRow> findForUpdate(ProcessInstanceId instanceId) {
      return delegate.findForUpdate(instanceId);
    }

    @Override
    public Optional<ProcessInstanceRow> findByBusinessKey(
        String tenantId, ProcessType processType, ProcessBusinessKey businessKey) {
      return delegate.findByBusinessKey(tenantId, processType, businessKey);
    }

    @Override
    public Optional<ProcessInstanceRow> readByBusinessKey(
        String tenantId, ProcessType processType, ProcessBusinessKey businessKey) {
      return delegate.readByBusinessKey(tenantId, processType, businessKey);
    }

    @Override
    public void insert(ProcessInstanceRow row, Instant now) {
      delegate.insert(row, now);
    }

    @Override
    public int updateSnapshot(ProcessInstanceRow row, ProcessRevision expected, Instant now) {
      return delegate.updateSnapshot(row, expected, now);
    }

    @Override
    public void suspend(
        ProcessInstanceId instanceId,
        ProcessLifecycle resumeLifecycle,
        String reason,
        String source,
        String workId,
        Instant now) {
      delegate.suspend(instanceId, resumeLifecycle, reason, source, workId, now);
    }

    @Override
    public void resume(ProcessInstanceId instanceId, ProcessLifecycle toLifecycle, Instant now) {
      delegate.resume(instanceId, toLifecycle, now);
    }

    @Override
    public Map<String, Long> countSuspendedBySource() {
      return delegate.countSuspendedBySource();
    }

    @Override
    public long countStuck(Instant updatedBefore) {
      return delegate.countStuck(updatedBefore);
    }

    @Override
    public List<ProcessInstanceRow> search(
        ProcessInstanceCriteria criteria, int limit, int offset) {
      return delegate.search(criteria, limit, offset);
    }

    @Override
    public List<ProcessInstanceRow> findStuck(Instant updatedBefore, int limit) {
      return delegate.findStuck(updatedBefore, limit);
    }

    @Override
    public List<VersionRef> distinctVersionsInUse() {
      return delegate.distinctVersionsInUse();
    }
  }
}
