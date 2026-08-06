package com.aipersimmon.ddd.processmanager.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionInsert;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
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
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessEffectStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessRetentionStore;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessTransitionStore;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The retention predicate, which is the policy.
 *
 * <p>It is worth testing against a database rather than a double because it is SQL, and because
 * what it must get right is a set of <em>refusals</em> — the states that look finished and are not.
 * A purge that is too eager destroys a business record; one that is too shy simply does nothing, so
 * the tests that matter are the ones asserting an instance was kept.
 */
class MybatisProcessRetentionStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final Instant LONG_AGO = NOW.minusSeconds(90_000);
  private static final Instant CUTOFF = NOW.minusSeconds(3_600);

  private JdbcTemplate jdbc;
  private ProcessStores stores;
  private MybatisProcessRetentionStore retention;
  private MybatisProcessInstanceStore instances;
  private MybatisProcessTransitionStore transitions;
  private MybatisProcessEffectStore effects;
  private MybatisProcessDeadlineStore deadlines;

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
            .addScript(
                "classpath:aipersimmon/db/migration/process-manager/h2/V5__retention_index.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    stores = ProcessStores.over(dataSource);
    retention = stores.retention();
    instances = stores.instances();
    transitions = stores.transitions();
    effects = stores.effects();
    deadlines = stores.deadlines();
  }

  /**
   * Rows are created through the real stores and only then nudged, so this fixture cannot drift
   * away from the schema the production code writes.
   */
  private void instance(String id, String lifecycle, Instant updatedAt) {
    instances.insert(
        new ProcessInstanceRow(
            "acme",
            new ProcessRef(
                new ProcessInstanceId(id),
                new ProcessType("Ordering"),
                new ProcessBusinessKey("key-" + id)),
            DefinitionVersion.INITIAL,
            StateSchemaVersion.INITIAL,
            ProcessLifecycle.RUNNING,
            new ProcessStep("done"),
            Optional.empty(),
            ProcessRevision.initial(),
            "sample.state",
            "{}".getBytes(StandardCharsets.UTF_8),
            Optional.empty(),
            Optional.empty()),
        LONG_AGO);
    jdbc.update(
        "UPDATE aipersimmon_process_instance SET lifecycle = ?, updated_at = ? WHERE instance_id = ?",
        lifecycle,
        Timestamp.from(updatedAt),
        id);
  }

  private void transitionOn(String instanceId) {
    transitions.append(
        new ProcessTransitionInsert(
            "acme",
            "t-" + instanceId,
            new ProcessInstanceId(instanceId),
            "m-" + instanceId,
            "sample.payload",
            1,
            "{}".getBytes(StandardCharsets.UTF_8),
            Optional.empty(),
            ProcessLifecycle.COMPLETED,
            Optional.empty(),
            new ProcessStep("done"),
            new DecisionCode("done"),
            "ADVANCE",
            "corr-1"),
        LONG_AGO);
  }

  private void effectOn(String instanceId, String status) {
    String effectId = "e-" + instanceId + "-" + status;
    effects.insert(
        new ProcessEffectInsert(
            "acme",
            effectId,
            new ProcessInstanceId(instanceId),
            "t-" + instanceId,
            0,
            effects.nextSeq(new ProcessInstanceId(instanceId)),
            ProcessEffectKind.DISPATCH_COMMAND,
            "sample.payload",
            1,
            "{}".getBytes(StandardCharsets.UTF_8),
            effectId,
            "corr-1",
            "cause-1",
            null,
            null),
        LONG_AGO);
    jdbc.update(
        "UPDATE aipersimmon_process_effect SET status = ? WHERE effect_id = ?", status, effectId);
  }

  private void deadlineOn(String instanceId, String status) {
    String deadlineId = "d-" + instanceId + "-" + status;
    deadlines.schedule(
        new ProcessDeadlineInsert(
            "acme",
            deadlineId,
            new ProcessInstanceId(instanceId),
            new DeadlineName("REVIEW"),
            deadlines.nextGeneration(new ProcessInstanceId(instanceId), new DeadlineName("REVIEW")),
            LONG_AGO,
            "sample.payload",
            1,
            "{}".getBytes(StandardCharsets.UTF_8),
            "corr-1",
            "cause-1",
            null,
            null),
        LONG_AGO);
    jdbc.update(
        "UPDATE aipersimmon_process_deadline SET status = ? WHERE deadline_id = ?",
        status,
        deadlineId);
  }

  private List<String> purgeable() {
    return retention.findPurgeable(CUTOFF, 100).stream().map(ProcessInstanceId::value).toList();
  }

  private long countIn(String table, String instanceId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE instance_id = ?", Long.class, instanceId);
  }

  @Test
  void anEndedInstanceWithNothingOwedIsPurgeable() {
    instance("i-1", "COMPLETED", LONG_AGO);
    effectOn("i-1", "DELIVERED");
    deadlineOn("i-1", "FIRED");

    assertEquals(List.of("i-1"), purgeable());
  }

  @Test
  void allThreeEndedLifecyclesQualify() {
    instance("i-1", "COMPLETED", LONG_AGO);
    instance("i-2", "FAILED", LONG_AGO);
    instance("i-3", "CANCELLED", LONG_AGO);

    // Same timestamp, so the tie-break on instance_id is what makes this assertable at all.
    assertEquals(List.of("i-1", "i-2", "i-3"), purgeable());
  }

  @Test
  void aLiveInstanceIsKeptHoweverOldItIs() {
    instance("i-1", "RUNNING", LONG_AGO);
    instance("i-2", "COMPENSATING", LONG_AGO);
    instance("i-3", "SUSPENDED", LONG_AGO);

    // Age is not the question for these — a suspended instance in particular has been sitting
    // untouched precisely because it is waiting for an operator.
    assertEquals(List.of(), purgeable());
  }

  @Test
  void anEndedInstanceInsideItsRetentionWindowIsKept() {
    instance("i-1", "COMPLETED", NOW.minusSeconds(60));

    assertEquals(List.of(), purgeable());
  }

  @Test
  void anEndedInstanceWithAnUndeliveredEffectIsKept() {
    instance("i-1", "COMPLETED", LONG_AGO);
    effectOn("i-1", "PENDING");
    instance("i-2", "COMPLETED", LONG_AGO);
    effectOn("i-2", "IN_FLIGHT");

    // A terminal decision cancels timers but its staged effects still go out — the final event of a
    // flow is exactly such an effect. Deleting the instance here would delete the effect with it.
    assertEquals(List.of(), purgeable());
  }

  @Test
  void anEndedInstanceWithDeadWorkIsKeptBecauseAnOperatorCanStillRedriveIt() {
    instance("i-1", "COMPLETED", LONG_AGO);
    effectOn("i-1", "DEAD");
    instance("i-2", "COMPLETED", LONG_AGO);
    deadlineOn("i-2", "DEAD");

    // A DEAD row is the record of a side effect that never landed. Removing it destroys the
    // evidence that something was owed, which is the opposite of what retention is for — the same
    // reason the outbox purge leaves the dead-letter table alone.
    assertEquals(List.of(), purgeable());
  }

  @Test
  void aCancelledOrFiredTimerDoesNotHoldAnInstanceBack() {
    instance("i-1", "COMPLETED", LONG_AGO);
    deadlineOn("i-1", "CANCELLED");
    effectOn("i-1", "CANCELLED");

    assertEquals(List.of("i-1"), purgeable());
  }

  @Test
  void theOldestGoFirstAndTheLimitIsRespected() {
    instance("i-old", "COMPLETED", LONG_AGO.minusSeconds(1_000));
    instance("i-new", "COMPLETED", LONG_AGO);

    List<ProcessInstanceId> first = retention.findPurgeable(CUTOFF, 1);

    assertEquals(List.of(new ProcessInstanceId("i-old")), first);
  }

  @Test
  void purgeRemovesTheInstanceAndEverythingRecordedAboutIt() {
    instance("i-1", "COMPLETED", LONG_AGO);
    transitionOn("i-1");
    effectOn("i-1", "DELIVERED");
    deadlineOn("i-1", "FIRED");

    assertEquals(1, retention.purge(List.of(new ProcessInstanceId("i-1"))));

    // All four, or none: an instance row whose transitions are gone is a state the runtime refuses
    // to answer about at all.
    assertEquals(0, countIn("aipersimmon_process_transition", "i-1"));
    assertEquals(0, countIn("aipersimmon_process_effect", "i-1"));
    assertEquals(0, countIn("aipersimmon_process_deadline", "i-1"));
    assertEquals(0, countIn("aipersimmon_process_instance", "i-1"));
  }

  @Test
  void purgeLeavesEveryOtherInstanceAlone() {
    instance("i-1", "COMPLETED", LONG_AGO);
    transitionOn("i-1");
    instance("i-2", "RUNNING", LONG_AGO);
    transitionOn("i-2");
    effectOn("i-2", "PENDING");

    retention.purge(List.of(new ProcessInstanceId("i-1")));

    assertEquals(1, countIn("aipersimmon_process_instance", "i-2"));
    assertEquals(1, countIn("aipersimmon_process_transition", "i-2"));
    assertEquals(1, countIn("aipersimmon_process_effect", "i-2"));
  }

  @Test
  void purgingNothingIssuesNoStatement() {
    instance("i-1", "COMPLETED", LONG_AGO);

    assertEquals(0, retention.purge(List.of()));

    assertTrue(countIn("aipersimmon_process_instance", "i-1") == 1);
  }
}
