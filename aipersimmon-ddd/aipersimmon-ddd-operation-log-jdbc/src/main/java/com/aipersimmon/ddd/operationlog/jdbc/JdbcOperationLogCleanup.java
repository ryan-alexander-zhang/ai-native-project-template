package com.aipersimmon.ddd.operationlog.jdbc;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes audit records older than the retention window, so the table does not grow for the
 * lifetime of the deployment. Opt-in and deliberately so twice over: deleting data and the right
 * retention are deployment decisions everywhere, and this table is an <em>audit</em> log — removing
 * its rows should be a statement someone can be asked about, never a default. Retention obligations
 * for audit data are often regulatory; the default window is a year, and enabling this asserts that
 * window is long enough.
 *
 * <p>Deletes in id pages ({@code record_id} is the single-column primary key), so the first purge
 * of a long-lived table is many small transactions rather than one giant DELETE. Not lock-guarded:
 * pages are cutoff-bounded and idempotent, so overlapping runs delete disjoint work.
 */
public class JdbcOperationLogCleanup {

  private static final Logger log = LoggerFactory.getLogger(JdbcOperationLogCleanup.class);

  private static final String SELECT_EXPIRED_PAGE =
      "SELECT record_id FROM aipersimmon_operation_log WHERE recorded_at < ? LIMIT ";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final long retentionSeconds;
  private final int batchSize;

  public JdbcOperationLogCleanup(
      JdbcTemplate jdbc, Clock clock, long retentionSeconds, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, was " + batchSize);
    }
    this.jdbc = jdbc;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.operation-log.cleanup.poll-delay-ms:3600000}")
  public void purge() {
    Timestamp cutoff = Timestamp.from(clock.instant().minusSeconds(retentionSeconds));
    int total = 0;
    while (true) {
      List<String> page = jdbc.queryForList(SELECT_EXPIRED_PAGE + batchSize, String.class, cutoff);
      if (page.isEmpty()) {
        break;
      }
      String placeholders = String.join(",", Collections.nCopies(page.size(), "?"));
      total +=
          jdbc.update(
              "DELETE FROM aipersimmon_operation_log WHERE record_id IN (" + placeholders + ")",
              page.toArray());
    }
    if (total > 0) {
      log.info(
          "operation-log cleanup removed {} audit records older than {}s", total, retentionSeconds);
    }
  }
}
