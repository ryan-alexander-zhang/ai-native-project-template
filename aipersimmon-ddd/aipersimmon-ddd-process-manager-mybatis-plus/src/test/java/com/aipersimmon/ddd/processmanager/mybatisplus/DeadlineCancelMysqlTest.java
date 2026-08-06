package com.aipersimmon.ddd.processmanager.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.mybatisplus.store.MybatisProcessDeadlineStore;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;

/**
 * The deadline cancel path on a real MySQL 8, exercising the shipped {@code mysql-schema.sql}.
 * {@code cancelCurrent} cancels the current-generation deadline for a name; an earlier form read
 * {@code MAX(generation)} from the same table in a subquery of the same UPDATE, which MySQL rejects
 * with ERROR 1093 ("can't specify target table for update in FROM clause"). The previous test suite
 * missed this because the deadline schedule/cancel path was only covered on H2 (which does not
 * enforce 1093) and the MySQL container test exercised only effect claiming. This is that missing
 * coverage: it must run clean on MySQL and actually flip the current generation to CANCELLED while
 * leaving older generations untouched.
 */
class DeadlineCancelMysqlTest {

  private static final MySQLContainer<?> MYSQL = SharedContainers.mysql();

  private JdbcTemplate jdbc;
  private ProcessStores stores;
  private MybatisProcessDeadlineStore deadlines;
  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
  private final ProcessInstanceId instance = new ProcessInstanceId("inst-1");
  private final DeadlineName name = new DeadlineName("review");

  @BeforeEach
  void setUp() throws Exception {
    var ds = TestDataSources.from(MYSQL);
    jdbc = new JdbcTemplate(ds);
    stores = ProcessStores.over(ds);
    // All four, not just the deadline table. V1 creates every table with IF NOT EXISTS and V2 then
    // drops a column from three of them, so leaving any table behind makes this setup depend on
    // which test class ran before it: the leftovers are already past V2, and V2 fails on them.
    for (String table :
        List.of(
            "aipersimmon_process_effect",
            "aipersimmon_process_transition",
            "aipersimmon_process_deadline",
            "aipersimmon_process_instance")) {
      jdbc.execute("DROP TABLE IF EXISTS " + table);
    }
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/mysql/V1__aipersimmon_process_manager.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/mysql/V2__drop_trace_id.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/mysql/V3__add_tenant_id.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/mysql/V4__parked_input_replay_marker.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/mysql/V5__retention_index.sql"))
        .execute(ds);
    deadlines = stores.deadlines();
  }

  @Test
  void cancelCurrentRunsOnMysqlAndCancelsOnlyTheCurrentGeneration() {
    deadlines.schedule(insert("d-gen1", 1L), NOW);
    deadlines.schedule(insert("d-gen2", 2L), NOW); // a reschedule bumped the generation

    // Before the fix this threw MySQL ERROR 1093 (self-referencing subquery in the UPDATE).
    deadlines.cancelCurrent(instance, name, NOW);

    assertEquals("CANCELLED", statusOf("d-gen2"), "the current (highest) generation is cancelled");
    assertEquals("PENDING", statusOf("d-gen1"), "an older generation is left untouched");
  }

  private ProcessDeadlineInsert insert(String deadlineId, long generation) {
    return new ProcessDeadlineInsert(
        Tenants.ROOT.value(),
        deadlineId,
        instance,
        name,
        generation,
        NOW.plusSeconds(600),
        "review-timeout",
        1,
        "{}".getBytes(StandardCharsets.UTF_8),
        "corr-1",
        "cause-1",
        null,
        null);
  }

  private String statusOf(String deadlineId) {
    return jdbc.queryForObject(
        "SELECT status FROM aipersimmon_process_deadline WHERE deadline_id = ?",
        String.class,
        deadlineId);
  }
}
