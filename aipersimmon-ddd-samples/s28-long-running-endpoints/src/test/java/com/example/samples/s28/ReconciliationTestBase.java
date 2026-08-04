package com.example.samples.s28;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s28.reconciliation.application.ExportClaims;
import com.example.samples.s28.reconciliation.application.ExportRunner;
import com.example.samples.s28.reconciliation.application.ExportSettings;
import com.example.samples.s28.reconciliation.application.ExportWorker;
import com.example.samples.s28.reconciliation.application.ProgressBoard;
import com.example.samples.s28.reconciliation.application.SubmitExport;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One context, one PostgreSQL, and the worker turned off.
 *
 * <p>The worker being off is the important line. Every test here drives exactly one run and asserts on its outcome,
 * and a background poller would claim the job first — so the test would be asserting on whatever the poller happened
 * to do, which is the sort of test that passes for years and then fails on a slow machine.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"s28.worker.enabled=false"})
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class ReconciliationTestBase {

  protected static final String PERIOD = "2026-06";

  @Autowired protected CommandBus commandBus;
  @Autowired protected QueryBus queryBus;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ExportJobs jobs;
  @Autowired protected ExportClaims claims;
  @Autowired protected ExportRunner runner;
  @Autowired protected ExportWorker worker;
  @Autowired protected ProgressBoard progress;
  @Autowired protected ExportSettings settings;

  @BeforeEach
  void emptyEverything() {
    jdbc.update("DELETE FROM s28_export_progress");
    jdbc.update("DELETE FROM s28_export_job");
    jdbc.update("DELETE FROM s28_import_chunk");
    jdbc.update("DELETE FROM s28_import_batch");
    jdbc.update("DELETE FROM s28_export_row");
    jdbc.update("DELETE FROM aipersimmon_process_effect");
    jdbc.update("DELETE FROM aipersimmon_process_deadline");
    jdbc.update("DELETE FROM aipersimmon_process_transition");
    jdbc.update("DELETE FROM aipersimmon_process_instance");
  }

  /** Rows for a period, in one statement, so a test can ask for a hundred thousand without waiting. */
  protected void seed(String period, int rows) {
    jdbc.update(
        "INSERT INTO s28_export_row (period, order_ref, amount_cents, note)"
            + " SELECT ?, 'ORD-' || lpad(g::text, 8, '0'), (g * 137) % 99999, 'settled'"
            + " FROM generate_series(1, ?) g",
        period,
        rows);
  }

  protected ExportJobId submit(String id) {
    commandBus.send(new SubmitExport(id, PERIOD));
    return new ExportJobId(id);
  }

  /** Claim as a named owner, so a test can play two workers. */
  protected ExportJobId claimAs(String owner) {
    return claims
        .claimNext(owner, settings.getLease(), java.time.Instant.now())
        .orElseThrow(() -> new AssertionError("nothing was claimable"));
  }

  /** Claim with an already-expired lease, to set up a takeover without waiting for one. */
  protected ExportJobId claimWithExpiredLease(String owner) {
    return claims
        .claimNext(owner, Duration.ofSeconds(-1), java.time.Instant.now())
        .orElseThrow(() -> new AssertionError("nothing was claimable"));
  }

  protected Map<String, Object> jobRow(String id) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM s28_export_job WHERE id = ?", id);
    if (rows.size() != 1) {
      throw new AssertionError("expected one job row for " + id + ", found " + rows.size());
    }
    return rows.get(0);
  }

  protected long jobVersion(String id) {
    return jdbc.queryForObject("SELECT version FROM s28_export_job WHERE id = ?", Long.class, id);
  }

  protected List<Map<String, Object>> progressRows() {
    return jdbc.queryForList("SELECT * FROM s28_export_progress");
  }
}
