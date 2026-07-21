package com.aipersimmon.ddd.processmanager.jdbc.lease;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Claim strategy for databases with {@code SELECT ... FOR UPDATE SKIP LOCKED} (PostgreSQL, MySQL
 * 8+). It selects and row-locks the due candidates, skipping rows a concurrent worker already
 * holds, then marks the locked rows {@code IN_FLIGHT} in the same transaction — so two workers
 * never claim the same effect.
 */
public final class SkipLockedProcessDialect implements JdbcProcessDialect {

  private final String id;

  public SkipLockedProcessDialect(String id) {
    this.id = id;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public List<String> claimDueEffects(
      JdbcTemplate jdbc,
      Instant now,
      int limit,
      WorkerId owner,
      String leaseToken,
      Instant leaseUntil) {
    Timestamp ts = Timestamp.from(now);
    List<String> ids =
        jdbc.query(
            CANDIDATE_SQL + " LIMIT " + limit + " FOR UPDATE SKIP LOCKED",
            (rs, n) -> rs.getString(1),
            ts,
            ts);
    for (String id : ids) {
      jdbc.update(
          """
                    UPDATE aipersimmon_process_effect
                    SET status = 'IN_FLIGHT',
                        lease_owner = ?, lease_token = ?, lease_until = ?, updated_at = ?
                    WHERE effect_id = ?""",
          owner.value(),
          leaseToken,
          Timestamp.from(leaseUntil),
          ts,
          id);
    }
    return ids;
  }

  @Override
  public List<String> claimDueDeadlines(
      JdbcTemplate jdbc,
      Instant now,
      int limit,
      WorkerId owner,
      String leaseToken,
      Instant leaseUntil) {
    Timestamp ts = Timestamp.from(now);
    // Lock only the deadline rows (OF d), not the joined instance, and skip contended ones.
    List<String> ids =
        jdbc.query(
            DEADLINE_CANDIDATE_SQL + " LIMIT " + limit + " FOR UPDATE OF d SKIP LOCKED",
            (rs, n) -> rs.getString(1),
            ts,
            ts);
    for (String id : ids) {
      jdbc.update(
          """
                    UPDATE aipersimmon_process_deadline
                    SET status = 'IN_FLIGHT',
                        lease_owner = ?, lease_token = ?, lease_until = ?, updated_at = ?
                    WHERE deadline_id = ?""",
          owner.value(),
          leaseToken,
          Timestamp.from(leaseUntil),
          ts,
          id);
    }
    return ids;
  }
}
