package com.aipersimmon.ddd.processmanager.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import com.aipersimmon.ddd.processmanager.engine.store.EffectStatus;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Which due effect the claim picks, and — the point of this class — which it must not refuse to
 * pick at all.
 *
 * <p>The claim used to order globally by {@code seq}. {@code seq} counts up per instance, so a
 * long-lived instance's is permanently larger than a young one's; ordered globally under a claim
 * limit, it sorted last on every poll and was never claimed. Not slowly — never. So the fairness
 * assertions here are about liveness, not niceness.
 *
 * <p>These run on H2 because they are about the SQL's meaning, which is the same everywhere. The
 * cost of the same SQL — the other half of issue-00125, where {@code <>} was not seekable — was
 * measured on real PostgreSQL and MySQL instead, since a plan is not a thing H2 can speak to.
 */
class JdbcProcessClaimFairnessTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final JdbcProcessDialect DIALECT = new AtomicUpdateProcessDialect("h2");

  private JdbcTemplate jdbc;
  private JdbcProcessInstanceStore instances;
  private JdbcProcessEffectStore effects;
  private JdbcProcessDeadlineStore deadlines;

  @BeforeEach
  void setUp() {
    String base = "classpath:aipersimmon/db/migration/process-manager/h2/";
    DataSource dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript(base + "V1__aipersimmon_process_manager.sql")
            .addScript(base + "V2__drop_trace_id.sql")
            .addScript(base + "V3__add_tenant_id.sql")
            .addScript(base + "V4__parked_input_replay_marker.sql")
            .addScript(base + "V5__retention_index.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    instances = new JdbcProcessInstanceStore(jdbc);
    effects = new JdbcProcessEffectStore(jdbc);
    deadlines = new JdbcProcessDeadlineStore(jdbc);
  }

  /**
   * The regression. An instance that has been running long enough to accumulate history has a high
   * seq; ten fresh instances sit at seq 0. Under a claim limit of five, the global {@code ORDER BY
   * seq} put the veteran behind every newcomer, on this poll and on every future one — a permanent
   * outage for one process instance while the queue as a whole looked healthy.
   */
  @Test
  void aLongLivedInstanceIsNotSortedBehindEveryYoungerOneForever() {
    instance("veteran");
    // Its history: 500 effects already delivered, so its next effect is at seq 500.
    pending("veteran", 500, "e-veteran", NOW.minus(Duration.ofMinutes(10)));
    for (int i = 0; i < 10; i++) {
      instance("fresh-" + i);
      pending("fresh-" + i, 0, "e-fresh-" + i, NOW.minus(Duration.ofMinutes(1)));
    }

    List<String> claimed = claimEffects(5);

    assertTrue(
        claimed.contains("e-veteran"),
        "the instance waiting longest was not claimed at all: " + claimed);
    assertEquals("e-veteran", claimed.get(0), "and it should be first — it has waited longest");
  }

  @Test
  void theEffectDueLongestAgoIsClaimedFirst() {
    instance("a");
    instance("b");
    instance("c");
    pending("a", 0, "e-a", NOW.minus(Duration.ofMinutes(1)));
    pending("b", 0, "e-b", NOW.minus(Duration.ofMinutes(9)));
    pending("c", 0, "e-c", NOW.minus(Duration.ofMinutes(5)));

    assertEquals(List.of("e-b", "e-c", "e-a"), claimEffects(10));
  }

  /**
   * Effects staged by one advance share a due instant exactly, so ties are the normal case. Without
   * a decisive tie-break the order is whatever the database happens to return, and a batch limit
   * can then hand back the same subset every poll while the rest waits forever — the same trap the
   * retention scan fell into (issue-00122).
   */
  @Test
  void effectsDueAtTheSameInstantAreOrderedDecisivelyById() {
    instance("x");
    instance("y");
    instance("z");
    pending("z", 0, "e-3", NOW);
    pending("x", 0, "e-1", NOW);
    pending("y", 0, "e-2", NOW);

    assertEquals(List.of("e-1", "e-2"), claimEffects(2));
  }

  @Test
  void deadlinesDueAtTheSameInstantAreOrderedDecisivelyById() {
    instance("x");
    instance("y");
    instance("z");
    deadline("z", "d-3");
    deadline("x", "d-1");
    deadline("y", "d-2");

    assertEquals(List.of("d-1", "d-2"), claimDeadlines(2));
  }

  /** Ordering is across instances only — within one instance the head still comes first, alone. */
  @Test
  void onlyTheHeadOfAnInstanceIsEverACandidate() {
    instance("a");
    // The later effect is the one that has been due longer, so ordering alone would prefer it.
    pending("a", 0, "e-head", NOW);
    pending("a", 1, "e-tail", NOW.minus(Duration.ofHours(1)));

    assertEquals(List.of("e-head"), claimEffects(10));
  }

  /**
   * The blocking predicate is spelled as two ranges around {@code 'DELIVERED'} for a reason that
   * has nothing to do with meaning, so its meaning is what needs pinning: every status that is not
   * {@code DELIVERED} must still block. Two of them sort below {@code 'DELIVERED'} and two above,
   * so this also exercises both ranges.
   */
  @Test
  void everyNotDeliveredStatusStillBlocksWhatIsBehindIt() {
    for (EffectStatus blocking : EffectStatus.values()) {
      setUp();
      String id = "inst-" + blocking;
      instance(id);
      pending(id, 0, "e-blocker", NOW);
      jdbc.update(
          "UPDATE aipersimmon_process_effect SET status = ? WHERE effect_id = 'e-blocker'",
          blocking.name());
      pending(id, 1, "e-behind", NOW);

      List<String> claimed = claimEffects(10);

      if (blocking == EffectStatus.DELIVERED) {
        assertEquals(List.of("e-behind"), claimed, "a delivered effect must not block");
      } else {
        assertTrue(
            !claimed.contains("e-behind"),
            "a " + blocking + " effect must still block what is behind it, got " + claimed);
      }
    }
  }

  private List<String> claimEffects(int limit) {
    return DIALECT.claimDueEffects(
        jdbc, NOW, limit, new WorkerId("w"), "token", NOW.plusSeconds(60));
  }

  private List<String> claimDeadlines(int limit) {
    return DIALECT.claimDueDeadlines(
        jdbc, NOW, limit, new WorkerId("w"), "token", NOW.plusSeconds(60));
  }

  private void instance(String id) {
    instances.insert(
        new ProcessInstanceRow(
            "acme",
            new ProcessRef(
                new ProcessInstanceId(id), new ProcessType("Ordering"), new ProcessBusinessKey(id)),
            DefinitionVersion.INITIAL,
            StateSchemaVersion.INITIAL,
            ProcessLifecycle.RUNNING,
            new ProcessStep("running"),
            Optional.empty(),
            ProcessRevision.initial(),
            "sample.state",
            "{}".getBytes(StandardCharsets.UTF_8),
            Optional.empty(),
            Optional.empty()),
        NOW);
  }

  /** Built through the real store so the fixture cannot drift from the production schema. */
  private void pending(String instanceId, long seq, String effectId, Instant dueAt) {
    effects.insert(
        new ProcessEffectInsert(
            "acme",
            effectId,
            new ProcessInstanceId(instanceId),
            "t-" + effectId,
            0,
            seq,
            ProcessEffectKind.DISPATCH_COMMAND,
            "sample.payload",
            1,
            "{}".getBytes(StandardCharsets.UTF_8),
            effectId,
            "corr-1",
            "cause-1",
            null,
            null),
        dueAt);
  }

  private void deadline(String instanceId, String deadlineId) {
    deadlines.schedule(
        new ProcessDeadlineInsert(
            "acme",
            deadlineId,
            new ProcessInstanceId(instanceId),
            new DeadlineName("REVIEW"),
            0,
            NOW,
            "sample.payload",
            1,
            "{}".getBytes(StandardCharsets.UTF_8),
            "corr-1",
            "cause-1",
            null,
            null),
        NOW);
  }
}
