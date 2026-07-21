package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@Testcontainers
class DeadlineCancelMysqlTest {

  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

  private JdbcTemplate jdbc;
  private JdbcProcessDeadlineStore deadlines;
  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
  private final ProcessInstanceId instance = new ProcessInstanceId("inst-1");
  private final DeadlineName name = new DeadlineName("review");

  @BeforeEach
  void setUp() throws Exception {
    SimpleDriverDataSource ds =
        new SimpleDriverDataSource(
            (java.sql.Driver)
                Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance(),
            MYSQL.getJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword());
    jdbc = new JdbcTemplate(ds);
    jdbc.execute("DROP TABLE IF EXISTS aipersimmon_process_deadline");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/mysql/V1__aipersimmon_process_manager.sql"),
            new ClassPathResource(
                "aipersimmon/db/migration/process-manager/mysql/V2__drop_trace_id.sql"))
        .execute(ds);
    deadlines = new JdbcProcessDeadlineStore(jdbc);
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
