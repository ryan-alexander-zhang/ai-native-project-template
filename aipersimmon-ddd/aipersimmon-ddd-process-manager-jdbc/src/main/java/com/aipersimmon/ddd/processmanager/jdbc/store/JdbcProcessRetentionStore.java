package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.engine.store.ProcessRetentionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** The JDBC retention store: finds finished instances safe to remove, and removes them whole. */
public final class JdbcProcessRetentionStore implements ProcessRetentionStore {

  /**
   * Ended, retention elapsed, and nothing still owed.
   *
   * <p>Ordered by {@code instance_id} within a timestamp so the order is total. Ties are the norm
   * here — a burst of instances finishing together shares a second — and with a batch limit an
   * unstable order could offer the same subset every run while another instance behind the tie is
   * never reached. The same reason the deadline claim orders by {@code (due_at, deadline_id)}.
   *
   * <p>The two {@code NOT EXISTS} clauses are the policy. {@code PENDING} and {@code IN_FLIGHT}
   * keep an instance because a terminal decision's staged effects still deliver after it ends;
   * {@code DEAD} keeps it because that row is the record of a side effect that never landed and an
   * operator can still redrive it. Everything else — delivered, fired, cancelled — is settled.
   */
  private static final String PURGEABLE =
      """
        SELECT i.instance_id FROM aipersimmon_process_instance i
        WHERE i.lifecycle IN ('COMPLETED', 'FAILED', 'CANCELLED')
          AND i.updated_at < ?
          AND NOT EXISTS (
              SELECT 1 FROM aipersimmon_process_effect e
              WHERE e.instance_id = i.instance_id
                AND e.status IN ('PENDING', 'IN_FLIGHT', 'DEAD'))
          AND NOT EXISTS (
              SELECT 1 FROM aipersimmon_process_deadline d
              WHERE d.instance_id = i.instance_id
                AND d.status IN ('PENDING', 'IN_FLIGHT', 'DEAD'))
        ORDER BY i.updated_at, i.instance_id""";

  private final JdbcTemplate jdbc;

  public JdbcProcessRetentionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ProcessInstanceId> findPurgeable(Instant endedBefore, int limit) {
    return jdbc.query(
        PURGEABLE + " LIMIT " + limit,
        (rs, n) -> new ProcessInstanceId(rs.getString("instance_id")),
        Timestamp.from(endedBefore));
  }

  @Override
  public int purge(List<ProcessInstanceId> instanceIds) {
    if (instanceIds.isEmpty()) {
      return 0;
    }
    Object[] ids = instanceIds.stream().map(ProcessInstanceId::value).toArray();
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.length, "?"));
    // Children first: an instance row with no transitions is a state the runtime refuses to answer
    // about, so a partial delete that left it behind would be worse than either extreme.
    jdbc.update(
        "DELETE FROM aipersimmon_process_effect WHERE instance_id IN (" + placeholders + ")", ids);
    jdbc.update(
        "DELETE FROM aipersimmon_process_deadline WHERE instance_id IN (" + placeholders + ")",
        ids);
    jdbc.update(
        "DELETE FROM aipersimmon_process_transition WHERE instance_id IN (" + placeholders + ")",
        ids);
    return jdbc.update(
        "DELETE FROM aipersimmon_process_instance WHERE instance_id IN (" + placeholders + ")",
        ids);
  }
}
