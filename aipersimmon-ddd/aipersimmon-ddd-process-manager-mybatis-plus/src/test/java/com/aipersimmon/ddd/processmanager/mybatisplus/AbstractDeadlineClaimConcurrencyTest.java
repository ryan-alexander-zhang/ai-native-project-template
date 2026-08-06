package com.aipersimmon.ddd.processmanager.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessInstanceStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code SKIP LOCKED} deadline claim, on a database that actually has {@code SKIP LOCKED}.
 *
 * <p>Until now this SQL had no coverage at all. The deadline tests all run on H2, and H2 takes the
 * {@link com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect} path — a
 * different statement entirely. So the shipped {@code FOR UPDATE OF d SKIP LOCKED}, which locks one
 * side of a join and is the least portable thing in the module, was reaching PostgreSQL and MySQL
 * for the first time in production. That is the same shape as the MariaDB defect (issue-00120): a
 * claim that does not parse fails every poll forever, and deadlines simply never fire.
 *
 * <p>The two halves worth separating: whether the statement <em>runs</em> here at all, and whether
 * it actually excludes a competing worker. A claim that silently hands the same deadline to two
 * workers fires a timer twice.
 */
abstract class AbstractDeadlineClaimConcurrencyTest {

  private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

  private JdbcTemplate jdbc;
  private ProcessStores stores;
  private TransactionTemplate transactions;
  private MybatisProcessInstanceStore instances;
  private MybatisProcessDeadlineStore deadlines;
  private String dialect;

  /** The container-backed data source for this database. */
  protected abstract DataSource dataSource();

  /** Dialect id, and the migration directory that goes with it. */
  protected abstract String vendor();

  @BeforeEach
  void setUp() {
    DataSource ds = dataSource();
    jdbc = new JdbcTemplate(ds);
    stores = ProcessStores.over(ds);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
    for (String table :
        List.of(
            "aipersimmon_process_effect",
            "aipersimmon_process_transition",
            "aipersimmon_process_deadline",
            "aipersimmon_process_instance")) {
      jdbc.execute("DROP TABLE IF EXISTS " + table);
    }
    String base = "aipersimmon/db/migration/process-manager/" + vendor() + "/";
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                base + "V1__aipersimmon_process_manager.sql"),
            new org.springframework.core.io.ClassPathResource(base + "V2__drop_trace_id.sql"),
            new org.springframework.core.io.ClassPathResource(base + "V3__add_tenant_id.sql"),
            new org.springframework.core.io.ClassPathResource(
                base + "V4__parked_input_replay_marker.sql"),
            new org.springframework.core.io.ClassPathResource(base + "V5__retention_index.sql"))
        .execute(ds);

    instances = stores.instances();
    deadlines = stores.deadlines();
    dialect = vendor();
  }

  /**
   * The statement has to be accepted by this database, with nothing due and with something due. A
   * claim that fails to parse is not a slow claim; it is a deadline that never fires, on every
   * poll, silently.
   */
  @Test
  void theClaimStatementRunsOnThisDatabase() {
    assertEquals(List.of(), claim(10), "an empty claim must still be a valid statement");

    instance("i-1", ProcessLifecycle.RUNNING);
    deadline("i-1", "d-1", NOW.minusSeconds(60));

    assertEquals(List.of("d-1"), claim(10));
  }

  /** Two workers polling at once must not both take the same timer. */
  @Test
  void twoWorkersClaimDisjointDeadlines() throws InterruptedException {
    int total = 40;
    for (int i = 0; i < total; i++) {
      String id = String.format("i-%02d", i);
      instance(id, ProcessLifecycle.RUNNING);
      deadline(id, String.format("d-%02d", i), NOW.minusSeconds(60));
    }

    List<String> a = Collections.synchronizedList(new ArrayList<>());
    List<String> b = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch start = new CountDownLatch(1);
    Thread one = worker(a, start);
    Thread two = worker(b, start);
    one.start();
    two.start();
    start.countDown();
    one.join(Duration.ofSeconds(60).toMillis());
    two.join(Duration.ofSeconds(60).toMillis());

    List<String> all = new ArrayList<>(a);
    all.addAll(b);
    assertEquals(total, all.size(), "every due deadline claimed exactly once");
    assertEquals(
        total, new HashSet<>(all).size(), "SKIP LOCKED handed the two workers disjoint sets");
    assertEquals(
        (long) total,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE status = 'IN_FLIGHT'",
            Long.class));
    assertTrue(
        !a.isEmpty() && !b.isEmpty(), "both workers took part: " + a.size() + "/" + b.size());
  }

  /**
   * The join predicate, on a real database. A suspended or ended instance keeps its timers — they
   * become claimable again if it resumes — so the claim must skip them rather than fire them.
   */
  @Test
  void onlyDeadlinesOfActiveInstancesAreClaimed() {
    instance("running", ProcessLifecycle.RUNNING);
    instance("compensating", ProcessLifecycle.COMPENSATING);
    instance("suspended", ProcessLifecycle.SUSPENDED);
    instance("completed", ProcessLifecycle.COMPLETED);
    deadline("running", "d-running", NOW.minusSeconds(60));
    deadline("compensating", "d-compensating", NOW.minusSeconds(60));
    deadline("suspended", "d-suspended", NOW.minusSeconds(60));
    deadline("completed", "d-completed", NOW.minusSeconds(60));

    assertEquals(List.of("d-compensating", "d-running"), claim(10));
  }

  /**
   * The tie-break added by issue-00125, checked where it has to hold. Deadlines are set from
   * business durations, so a batch falling due on the same instant is ordinary; without a decisive
   * order a claim limit can return the same subset every poll and leave the rest unfired.
   */
  @Test
  void deadlinesDueAtTheSameInstantAreOrderedById() {
    for (String id : List.of("c", "a", "b")) {
      instance("i-" + id, ProcessLifecycle.RUNNING);
      deadline("i-" + id, "d-" + id, NOW.minusSeconds(60));
    }

    assertEquals(List.of("d-a", "d-b"), claim(2));
  }

  private List<String> claim(int limit) {
    return transactions.execute(
        status ->
            stores
                .claims(dialect, new WorkerId("w"))
                .claimDueDeadlines(NOW, limit, "token", NOW.plusSeconds(300)));
  }

  private Thread worker(List<String> claimed, CountDownLatch start) {
    return new Thread(
        () -> {
          try {
            start.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          int idle = 0;
          while (idle < 20) {
            List<String> batch =
                transactions.execute(
                    status ->
                        stores
                            .claims(dialect, new WorkerId(Thread.currentThread().getName()))
                            .claimDueDeadlines(
                                NOW,
                                5,
                                "token-" + Thread.currentThread().getName(),
                                NOW.plusSeconds(300)));
            if (batch == null || batch.isEmpty()) {
              idle++;
            } else {
              claimed.addAll(batch);
              idle = 0;
            }
          }
        });
  }

  private void instance(String id, ProcessLifecycle lifecycle) {
    instances.insert(
        new ProcessInstanceRow(
            "acme",
            new ProcessRef(
                new ProcessInstanceId(id), new ProcessType("Ordering"), new ProcessBusinessKey(id)),
            DefinitionVersion.INITIAL,
            StateSchemaVersion.INITIAL,
            lifecycle,
            new ProcessStep("running"),
            Optional.empty(),
            ProcessRevision.initial(),
            "sample.state",
            "{}".getBytes(StandardCharsets.UTF_8),
            Optional.empty(),
            Optional.empty()),
        NOW);
  }

  private void deadline(String instanceId, String deadlineId, Instant dueAt) {
    deadlines.schedule(
        new ProcessDeadlineInsert(
            "acme",
            deadlineId,
            new ProcessInstanceId(instanceId),
            new DeadlineName("REVIEW"),
            0,
            dueAt,
            "sample.payload",
            1,
            "{}".getBytes(StandardCharsets.UTF_8),
            "corr-1",
            "cause-1",
            null,
            null),
        dueAt);
  }
}
